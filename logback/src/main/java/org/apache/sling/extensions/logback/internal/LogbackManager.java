package org.apache.sling.extensions.logback.internal;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.gaffer.GafferUtil;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggerContextAwareBase;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.util.EnvUtil;
import ch.qos.logback.core.joran.GenericConfigurator;
import ch.qos.logback.core.joran.event.SaxEvent;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusListenerAsList;
import ch.qos.logback.core.status.StatusUtil;
import ch.qos.logback.core.util.StatusPrinter;
import org.osgi.framework.BundleContext;
import org.slf4j.LoggerFactory;

public class LogbackManager extends LoggerContextAwareBase {
    private static final String PREFIX  = "org.apache.sling.commons.log";
    private static final String DEBUG = PREFIX + "." + "debug";
    private final String rootDir;
    private final String contextName = "sling";
    private final LogConfigManager logConfigManager;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    private final List<LogbackResetListener> resetListeners = new ArrayList<LogbackResetListener>();

    /**
     * Acts as a bridge between Logback and OSGi
     */
    private final LoggerContextListener osgiIntegrationListener = new OsgiIntegrationListener();

    private final boolean debug;

    private final boolean started;

    public LogbackManager(BundleContext bundleContext) {
        setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
        this.rootDir = bundleContext.getProperty("sling.home");
        this.debug = Boolean.parseBoolean(bundleContext.getProperty(DEBUG));

        //TODO Make it configurable
        getLoggerContext().setName(contextName);
        this.logConfigManager = new LogConfigManager(getLoggerContext(),bundleContext, rootDir,this);
        resetListeners.add(logConfigManager);
        getLoggerContext().addListener(osgiIntegrationListener);

        configure();
        started = true;
    }

    private void configure() {
        ConfiguratorCallback cb = new DefaultCallback();

        //Check first for an explicit configuration file
        File configFile = logConfigManager.getLogbackConfigFile();
        if(configFile != null){
           cb = new FilenameConfiguratorCallback(configFile);
        }

        configure(cb);
    }

    public void configure(ConfiguratorCallback cb) {
        StatusListener statusListener = new StatusListenerAsList();
        if(debug){
            statusListener = new OnConsoleStatusListener();
        }

        getStatusManager().add(statusListener);
        addInfo("Resetting context: " + getLoggerContext().getName());
        resetContext(statusListener);

        StatusUtil statusUtil = new StatusUtil(getLoggerContext());
        JoranConfigurator configurator = createConfigurator();
        final List<SaxEvent> eventList = configurator.recallSafeConfiguration();
        final long threshold = System.currentTimeMillis();

        try {
            cb.perform(configurator);
            if (statusUtil.hasXMLParsingErrors(threshold)) {
                cb.fallbackConfiguration(eventList,createConfigurator(),statusListener);
            }
            addInfo("Context: " + getLoggerContext().getName() + " reloaded.");
        } catch(JoranException je){
            cb.fallbackConfiguration(eventList, createConfigurator(), statusListener);
        } finally {
            getStatusManager().remove(statusListener);
            StatusPrinter.printInCaseOfErrorsOrWarnings(getLoggerContext());
        }
    }

    public void shutdown() {
        getLoggerContext().removeListener(osgiIntegrationListener);

        logConfigManager.close();

        getLoggerContext().stop();
    }

    public void configChanged(){
        if(!started){
            return;
        }
        log.info("Configuration change detected. Logback config would be reloaded");
        //TODO Need to see if this has to be done in asynchronous manner
        //As during initial startup config would be updated frequently causing
        //LogBack to reset very quickly
        configure();
    }

    private JoranConfigurator createConfigurator(){
        SlingConfigurator configurator = new SlingConfigurator();
        configurator.setContext(getLoggerContext());
        return configurator;
    }

    private void resetContext(StatusListener statusListener){
        getLoggerContext().reset();
        // after a reset the statusListenerAsList gets removed as a listener
        if(statusListener != null
                && !getStatusManager().getCopyOfStatusListenerList().contains(statusListener)){
             getStatusManager().add(statusListener);
        }
    }

    //~-------------------------------LogggerContextListener

    private class OsgiIntegrationListener implements LoggerContextListener {

        public boolean isResetResistant() {
            //The integration listener has to survive resets from other causes
            //like reset when Logback detects change in config file and reloads on
            //on its own in ReconfigureOnChangeFilter
            return true;
        }

        public void onStart(LoggerContext context) {
        }

        public void onReset(LoggerContext context) {
            for(LogbackResetListener l : resetListeners){
                l.onReset(context);
            }
        }

        public void onStop(LoggerContext context) {
        }

        public void onLevelChange(Logger logger, Level level) {
        }

    }

    //~--------------------------------Configurator Base

    private class SlingConfigurator extends JoranConfigurator {

        @Override
        protected void buildInterpreter() {
            super.buildInterpreter();
            interpreter.getInterpretationContext().addSubstitutionProperty("sling.home", rootDir);
        }
    }

    //~--------------------------------Configuration Support

    private abstract class ConfiguratorCallback {
        abstract void perform(JoranConfigurator configurator) throws JoranException;

        /**
         * Logic based on ch.qos.logback.classic.turbo.ReconfigureOnChangeFilter.ReconfiguringThread
         */
        public void fallbackConfiguration(List<SaxEvent> eventList, JoranConfigurator configurator,
                                          StatusListener statusListener) {
            URL mainURL = getMainUrl();
            if (mainURL != null) {
                if (eventList != null) {
                    addWarn("Falling back to previously registered safe configuration.");
                    try {
                        resetContext(statusListener);
                        GenericConfigurator.informContextOfURLUsedForConfiguration(context, mainURL);
                        configurator.doConfigure(eventList);
                        addInfo("Re-registering previous fallback configuration once more as a fallback configuration point");
                        configurator.registerSafeConfiguration();
                    } catch (JoranException e) {
                        addError("Unexpected exception thrown by a configuration considered safe.", e);
                    }
                } else {
                    addWarn("No previous configuration to fall back on.");
                }
            }
        }

        protected URL getMainUrl() {
            return null;
        }
    }

    private class FilenameConfiguratorCallback extends ConfiguratorCallback {
        private final File configFile;

        public FilenameConfiguratorCallback(File configFile) {
            this.configFile = configFile;
        }

        public void perform(JoranConfigurator configurator) throws JoranException {
            final String path = configFile.getAbsolutePath();
            addInfo("Configuring from " + path);
            if (configFile.getName().endsWith("xml")) {
                configurator.doConfigure(configFile);
            } else if (configFile.getName().endsWith("groovy")) {
                if (EnvUtil.isGroovyAvailable()) {
                    // avoid directly referring to GafferConfigurator so as to avoid
                    // loading  groovy.lang.GroovyObject . See also http://jira.qos.ch/browse/LBCLASSIC-214
                    GafferUtil.runGafferConfiguratorOn(getLoggerContext(), this, configFile);
                } else {
                    addError("Groovy classes are not available on the class path. ABORTING INITIALIZATION.");
                }
            }
        }

        @Override
        protected URL getMainUrl() {
            try {
                return configFile.toURI().toURL();
            } catch (MalformedURLException e) {
                addWarn("Cannot convert file to url " + configFile.getAbsolutePath(),e);
                return null;
            }
        }
    }

    private class DefaultCallback extends ConfiguratorCallback {
        public void perform(JoranConfigurator configurator) throws JoranException {
            configurator.doConfigure(getMainUrl());
        }

        @Override
        protected URL getMainUrl() {
            return getClass().getClassLoader().getResource("logback-empty.xml");
        }
    }
}
