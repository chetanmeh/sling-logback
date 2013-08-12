package org.apache.sling.extensions.logback.internal;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.joran.spi.ConfigurationWatchList;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusListenerAsList;
import ch.qos.logback.core.status.StatusManager;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.StatusPrinter;
import org.osgi.framework.BundleContext;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

public class LogbackManager {
    private static final String PREFIX  = "org.apache.sling.commons.log";
    private static final String CONFIG_FILE_PROPERTY = PREFIX + "." + ContextInitializer.CONFIG_FILE_PROPERTY;
    private final LoggerContext loggerContext;
    private final ContextUtil contextUtil;
    private final String rootDir;
    private final String contextName = "sling";
    private final LogConfigManager logConfigManager;

    private volatile boolean configChanged;

    private final List<LogbackResetListener> resetListeners = new ArrayList<LogbackResetListener>();

    /**
     * Acts as a bridge between Logback and OSGi
     */
    private final LoggerContextListener osgiIntegrationListener = new OsgiIntegrationListener();

    private final boolean debug = true;

    public LogbackManager(BundleContext bundleContext) {
        this.loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.contextUtil = new ContextUtil(loggerContext);
        this.rootDir = bundleContext.getProperty("sling.home");

        //TODO Make it configurable
        this.loggerContext.setName(contextName);
        this.logConfigManager = new LogConfigManager(loggerContext,bundleContext, rootDir,this);
        this.loggerContext.addListener(osgiIntegrationListener);

        resetListeners.add(logConfigManager);

        configure(bundleContext);
    }

    private void configure(BundleContext bundleContext) {
        ConfiguratorCallback cb = new DefaultCallback();

        //Check first for an explicit configuration file
        String configFile = bundleContext.getProperty(CONFIG_FILE_PROPERTY);
        if(configFile != null){
           cb = new FilenameConfiguratorCallback(configFile);
        }
        configure(cb);
    }

    public void configure(ConfiguratorCallback cb) {
        StatusListenerAsList statusListenerAsList = new StatusListenerAsList();

        addStatusListener(statusListenerAsList);
        contextUtil.addInfo("Resetting context: " + loggerContext.getName());
        loggerContext.reset();
        // after a reset the statusListenerAsList gets removed as a listener
        addStatusListener(statusListenerAsList);

        try {
            SlingConfigurator configurator = new SlingConfigurator();
            configurator.setContext(loggerContext);
            cb.perform(configurator);
            contextUtil.addInfo("Context: " + loggerContext.getName() + " reloaded.");
        } catch(JoranException je){
            // StatusPrinter will handle this
        } finally {
            removeStatusListener(statusListenerAsList);
            if (debug) {
                StatusPrinter.print(statusListenerAsList.getStatusList());
            }else{
                StatusPrinter.printInCaseOfErrorsOrWarnings(loggerContext);
            }
        }
    }

    public void shutdown() {
        loggerContext.removeListener(osgiIntegrationListener);

        logConfigManager.close();

        loggerContext.stop();
    }

    public void configChanged(){
        configChanged = true;
    }

    //~-----------------------------Internal Utility Methods

    private void addStatusListener(StatusListener statusListener) {
        StatusManager sm = loggerContext.getStatusManager();
        sm.add(statusListener);
    }

    private void removeStatusListener(StatusListener statusListener) {
        StatusManager sm = loggerContext.getStatusManager();
        sm.remove(statusListener);
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
            addCustomConfigWatchList(context);

            for(LogbackResetListener l : resetListeners){
                l.onReset(context);
            }

        }

        private void addCustomConfigWatchList(LoggerContext context) {
            ConfigurationWatchList cwl = new OsgiAwareConfigurationWatchList();
            cwl.setContext(context);
            context.putObject(CoreConstants.CONFIGURATION_WATCH_LIST, cwl);
        }

        public void onStop(LoggerContext context) {
        }

        public void onLevelChange(Logger logger, Level level) {
        }

    }

    //--------------------------------ConfigurationWatchList

    private class OsgiAwareConfigurationWatchList extends ConfigurationWatchList {
        @Override
        public boolean changeDetected() {
            if(configChanged){
                //Reset the flag to false once it is read
                configChanged = false;
                return true;
            }
            return super.changeDetected();
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

    private static interface ConfiguratorCallback {
        void perform(JoranConfigurator configurator) throws JoranException;
    }

    private class InputSourceConfiguratorCallback implements ConfiguratorCallback {
        private final InputSource is;

        public InputSourceConfiguratorCallback(InputSource is) {
            this.is = is;
        }

        public void perform(JoranConfigurator configurator) throws JoranException {
            configurator.doConfigure(is);
        }
    }

    private class FilenameConfiguratorCallback implements ConfiguratorCallback {
        private final String fileName;

        public FilenameConfiguratorCallback(String fileName) {
            this.fileName = fileName;
        }

        public void perform(JoranConfigurator configurator) throws JoranException {
            String fileName = this.fileName.trim();
            File file = new File(fileName);
            if(!file.isAbsolute()){
                file = new File(rootDir,fileName);
            }
            final String path = file.getAbsolutePath();
            contextUtil.addInfo("Configuring from "+path);
            if(!file.exists()){
                contextUtil.addWarn("File does not exist "+path);
                return;
            } else if(!file.canRead()){
                contextUtil.addWarn("Cannot read file "+path);
                return;
            }
            configurator.doConfigure(file);
        }
    }

    private class DefaultCallback implements ConfiguratorCallback {
        public void perform(JoranConfigurator configurator) throws JoranException {
            URL url = getClass().getClassLoader().getResource("logback.xml");
            configurator.doConfigure(url);
        }
    }
}
