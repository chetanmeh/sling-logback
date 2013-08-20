package org.apache.sling.extensions.logback.internal;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.gaffer.GafferUtil;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextAwareBase;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.util.EnvUtil;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.joran.GenericConfigurator;
import ch.qos.logback.core.joran.event.SaxEvent;
import ch.qos.logback.core.joran.spi.InterpretationContext;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusListenerAsList;
import ch.qos.logback.core.status.StatusUtil;
import ch.qos.logback.core.util.StatusPrinter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.LoggerFactory;

public class LogbackManager extends LoggerContextAwareBase {
    private static final String PREFIX  = "org.apache.sling.commons.log";
    private static final String DEBUG = PREFIX + "." + "debug";
    private final String rootDir;
    private final String contextName = "sling";
    private final LogConfigManager logConfigManager;

    private final List<LogbackResetListener> resetListeners = new ArrayList<LogbackResetListener>();

    /**
     * Acts as a bridge between Logback and OSGi
     */
    private final LoggerContextListener osgiIntegrationListener = new OsgiIntegrationListener();

    private final boolean debug;

    private final boolean started;

    private volatile boolean resetInProgress;

    private final AtomicBoolean configChanged = new AtomicBoolean();

    private final AppenderTracker appenderTracker;

    private final ConfigSourceTracker configSourceTracker;

    private ServiceRegistration panelRegistration;
    private ServiceRegistration printerRegistration;

    public LogbackManager(BundleContext bundleContext) throws InvalidSyntaxException {
        setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
        this.rootDir = bundleContext.getProperty("sling.home");
        this.debug = Boolean.parseBoolean(bundleContext.getProperty(DEBUG));

        this.appenderTracker = new AppenderTracker(bundleContext,getLoggerContext());
        this.configSourceTracker = new ConfigSourceTracker(bundleContext,this);

        //TODO Make it configurable
        getLoggerContext().setName(contextName);
        this.logConfigManager = new LogConfigManager(getLoggerContext(),bundleContext, rootDir,this);

        resetListeners.add(logConfigManager);
        resetListeners.add(appenderTracker);
        resetListeners.add(configSourceTracker);

        getLoggerContext().addListener(osgiIntegrationListener);

        configure();
        registerWebConsoleSupport(bundleContext);
        started = true;
    }

    public void shutdown() {
        if(panelRegistration != null){
            panelRegistration.unregister();
        }

        if(printerRegistration != null){
            printerRegistration.unregister();
        }

        appenderTracker.close();
        configSourceTracker.close();
        getLoggerContext().removeListener(osgiIntegrationListener);
        logConfigManager.close();
        getLoggerContext().stop();
    }

    public void configChanged(){
        if(!started){
            return;
        }
        if(resetInProgress){
            configChanged.set(true);
            addInfo("LoggerContext reset in progress. Marking config changed to true");
            return;
        }
        scheduleConfigReload();
    }

    public LogConfigManager getLogConfigManager() {
        return logConfigManager;
    }

    public AppenderTracker getAppenderTracker() {
        return appenderTracker;
    }

    public ConfigSourceTracker getConfigSourceTracker() {
        return configSourceTracker;
    }

    public void addSubsitutionProperties(InterpretationContext ic){
        ic.addSubstitutionProperty("sling.home", rootDir);
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

    private void configure(ConfiguratorCallback cb) {
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

    private void scheduleConfigReload(){
        getLoggerContext().getExecutorService().submit(new LoggerReconfigurer());
    }

    private class LoggerReconfigurer implements Runnable {

        public void run() {
            resetInProgress = true;
            addInfo("Performing configuration");
            configure();
            boolean configChanged = LogbackManager.this.configChanged.getAndSet(false);
            if(configChanged){
                addInfo("Config change detected while reset was in progress. Rescheduling new config reset");
                scheduleConfigReload();
            }else{
                addInfo("Re configuration done");
                resetInProgress = false;
            }
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
            context.putObject(LogbackManager.class.getName(),LogbackManager.this);
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
            addSubsitutionProperties(interpreter.getInterpretationContext());
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

    //~ ----------------------------------------------WebConsole Support

    public LoggerStateContext determineLoggerState(){
        final List<Logger> loggers = getLoggerContext().getLoggerList();
        final LoggerStateContext ctx = new LoggerStateContext(loggers);
        for(Logger logger : loggers){
            if(logger.iteratorForAppenders().hasNext() || logger.getLevel() != null){
                ctx.loggerInfos.add(logger);
            }

            Iterator<Appender<ILoggingEvent>> itr = logger.iteratorForAppenders();
            while(itr.hasNext()){
                Appender<ILoggingEvent> a = itr.next();
                if(a.getName() != null && !ctx.appenders.containsKey(a.getName())){
                    ctx.appenders.put(a.getName(),a);
                }
            }
        }
        return ctx;
    }

    public class LoggerStateContext {
        final LoggerContext loggerContext = getLoggerContext();
        final List<Logger> allLoggers;
        /**
         * List of logger which have explicitly defined level or appenders set
         */
        final List<Logger> loggerInfos = new ArrayList<Logger>();
        final Map<String,Appender<ILoggingEvent>> appenders = new HashMap<String, Appender<ILoggingEvent>>();
        final Map<Appender<ILoggingEvent>, AppenderTracker.AppenderInfo> dynamicAppenders =
                new HashMap<Appender<ILoggingEvent>, AppenderTracker.AppenderInfo>();

        private LoggerStateContext(List<Logger> allLoggers) {
            this.allLoggers = allLoggers;
            for(AppenderTracker.AppenderInfo ai : getAppenderTracker().getAppenderInfos()){
                dynamicAppenders.put(ai.appender, ai);
            }
        }

        int getNumberOfLoggers(){
            return allLoggers.size();
        }

        int getNumofSlingLogConfig(){
            return getLogConfigManager().getConfigByPid().size();
        }

        int getNumofSlingLogWriters(){
            return getLogConfigManager().getWriterByPid().size();
        }

        int getNumOfDynamicAppenders(){
            return getAppenderTracker().getAppenderInfos().size();
        }

        int getNumOfLogbackAppenders(){
            return appenders.size()
                    - getNumofSlingLogWriters()
                    - getNumOfDynamicAppenders();
        }

        boolean isDynamicAppender(Appender<ILoggingEvent> a){
            return dynamicAppenders.containsKey(a);
        }

        Collection<Appender<ILoggingEvent>> getAllAppenders(){
            return appenders.values();
        }
    }

    private void registerWebConsoleSupport(BundleContext context){
        final ServiceFactory serviceFactory = new PluginServiceFactory();

        Properties pluginProps = new Properties();
        pluginProps.put(Constants.SERVICE_VENDOR, "Apache Software Foundation");
        pluginProps.put(Constants.SERVICE_DESCRIPTION, "Sling Log Support");
        pluginProps.put("felix.webconsole.label", "slinglogback");
        pluginProps.put("felix.webconsole.title", "Sling Log Support");

        panelRegistration=  context.registerService("javax.servlet.Servlet",serviceFactory, pluginProps);

        Properties printerProps = new Properties();
        printerProps.put(Constants.SERVICE_VENDOR, "Apache Software Foundation");
        printerProps.put(Constants.SERVICE_DESCRIPTION, "Sling Log Support");
        printerProps.put("felix.webconsole.label", "slinglogbacklogs");
        printerProps.put("felix.webconsole.title", "Log Files");
        printerProps.put("felix.webconsole.configprinter.modes", "always");

        //TODO need to see to add support for Inventory Feature
        printerRegistration=  context.registerService(SlingConfigurationPrinter.class.getName(),
                new SlingConfigurationPrinter(this), printerProps);
    }

    private class PluginServiceFactory implements ServiceFactory {
        private Object instance;

        public Object getService(Bundle bundle, ServiceRegistration registration) {
            synchronized (this) {
                if (this.instance == null) {
                    this.instance = new SlingLogPanel(LogbackManager.this);
                }
                return instance;
            }
        }

        public void ungetService(Bundle bundle, ServiceRegistration registration, Object service) {
        }
    }
}
