package org.apache.sling.extensions.logback.internal;

import java.io.File;
import java.net.URL;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusListenerAsList;
import ch.qos.logback.core.status.StatusManager;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.StatusPrinter;
import org.apache.sling.extensions.logback.internal.config.LogConfigManager;
import org.osgi.framework.BundleContext;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

public class LogbackManager {
    private static final String PREFIX  = "sling";
    private static final String CONFIG_FILE_PROPERTY = PREFIX + "." + ContextInitializer.CONFIG_FILE_PROPERTY;
    private final LoggerContext loggerContext;
    private final ContextUtil contextUtil;
    private final String rootDir;
    private final BundleContext bundleContext;
    private final String contextName = "sling";
    private final LogConfigManager logConfigManager;

    private final boolean debug = true;

    public LogbackManager(BundleContext bundleContext) {
        this.loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        this.contextUtil = new ContextUtil(loggerContext);
        this.bundleContext = bundleContext;
        this.rootDir = bundleContext.getProperty("sling.home");

        //TODO Make it configurable
        this.loggerContext.setName(contextName);

        configure(bundleContext);

        this.logConfigManager = new LogConfigManager(loggerContext,bundleContext, rootDir);
    }

    private void configure(BundleContext bundleContext) {
        ConfigureCallback cb = new DefaultCallback();

        //Check first for an explicit configuration file
        String configFile = bundleContext.getProperty(CONFIG_FILE_PROPERTY);
        if(configFile != null){
           cb = new FilenameConfigureCallback(configFile);
        }
        configure(cb);
    }

    public void configure(ConfigureCallback cb) {
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
        logConfigManager.close();
        loggerContext.stop();
    }

    private void addStatusListener(StatusListener statusListener) {
        StatusManager sm = loggerContext.getStatusManager();
        sm.add(statusListener);
    }

    private void removeStatusListener(StatusListener statusListener) {
        StatusManager sm = loggerContext.getStatusManager();
        sm.remove(statusListener);
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

    private static interface ConfigureCallback {
        void perform(JoranConfigurator configurator) throws JoranException;
    }

    private class InputSourceConfigureCallback implements ConfigureCallback {
        private final InputSource is;

        public InputSourceConfigureCallback(InputSource is) {
            this.is = is;
        }

        public void perform(JoranConfigurator configurator) throws JoranException {
            configurator.doConfigure(is);
        }
    }

    private class FilenameConfigureCallback implements ConfigureCallback {
        private final String fileName;

        public FilenameConfigureCallback(String fileName) {
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

    private class DefaultCallback implements ConfigureCallback {
        public void perform(JoranConfigurator configurator) throws JoranException {
            URL url = getClass().getClassLoader().getResource("logback.xml");
            configurator.doConfigure(url);
        }
    }
}
