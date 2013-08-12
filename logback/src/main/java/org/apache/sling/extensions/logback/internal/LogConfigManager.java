/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.extensions.logback.internal;

import java.io.File;
import java.util.Collection;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.util.ContextUtil;
import org.apache.sling.extensions.logback.internal.config.ConfigAdminSupport;
import org.apache.sling.extensions.logback.internal.config.ConfigurationException;
import org.apache.sling.extensions.logback.internal.util.LoggerSpecificEncoder;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;

public class LogConfigManager implements LogbackResetListener{

    public static final String LOG_LEVEL = "org.apache.sling.commons.log.level";

    public static final String LOG_FILE = "org.apache.sling.commons.log.file";

    public static final String LOG_FILE_NUMBER = "org.apache.sling.commons.log.file.number";

    public static final String LOG_FILE_SIZE = "org.apache.sling.commons.log.file.size";

    public static final String LOG_PATTERN = "org.apache.sling.commons.log.pattern";

    public static final String LOG_PATTERN_DEFAULT = "%d{dd.MM.yyyy HH:mm:ss.SSS} *%level* [%thread] %logger %msg%n";

    public static final String LOG_LOGGERS = "org.apache.sling.commons.log.names";

    public static final String LOG_LEVEL_DEFAULT = "INFO";

    public static final int LOG_FILE_NUMBER_DEFAULT = 5;

    public static final String LOG_FILE_SIZE_DEFAULT = "'.'yyyy-MM-dd";

    public static final String PID = "org.apache.sling.commons.log.LogManager";

    public static final String FACTORY_PID_WRITERS = PID + ".factory.writer";

    public static final String FACTORY_PID_CONFIGS = PID + ".factory.config";

    public static final String ROOT = "";

    private final LoggerContext loggerContext;

    private final ContextUtil contextUtil;

    // map of log writers indexed by configuration PID
    private final Map<String, LogWriter> writerByPid;

    // map of log writers indexed by (absolute) file name. This map does
    // not contain writers writing to standard out
    private final Map<String, LogWriter> writerByFileName;

    // map of appenders indexed by LogWriter filename and LogConfig pattern
//    private final Map<AppenderKey, Appender<ILoggingEvent>> appenderByKey;

    // map of log configurations by configuration PID
    private final Map<String, LogConfig> configByPid;

    // map of log configurations by the categories they are configured with
    private final Map<String, LogConfig> configByCategory;

    // map of all loggers supplied by getLogger(String) by their names. Each
    // entry is in fact a SoftReference to the actual logger, such that the
    // loggers may be cleaned up if no used any more.
    // There is no ReferenceQueue handling currently for removed loggers
//    private final Map<String, SoftReference<SlingLogger>> loggersByCategory;

    // the default logger configuration set up by the constructor and managed
    // by the global logger configuration
    private LogConfig defaultLoggerConfig;

    // the default writer configuration set up by the constructor and managed
    // by the global logger configuration
    private LogWriter defaultWriter;

    // the root folder to make relative writer paths absolute
    private final File rootDir;

    // global default configuration (from BundleContext properties)
    private Dictionary<String, String> defaultConfiguration;

    private final ConfigAdminSupport configAdminSupport;

    private final LogbackManager logbackManager;

    /**
     * Logs a message an optional stack trace to error output. This method is
     * used by the logging system in case of errors writing to the correct
     * logging output.
     */
    public void internalFailure(String message, Throwable t) {
        if(t != null){
            contextUtil.addError(message,t);
        }else{
            contextUtil.addError(message);
        }
    }

    /**
     * Sets up this log configuration manager by creating the default writers
     * and logger configuration
     */
    public LogConfigManager(LoggerContext loggerContext, BundleContext bundleContext, String rootDir, LogbackManager logbackManager) {
        this.logbackManager = logbackManager;
        this.loggerContext = loggerContext;
        contextUtil = new ContextUtil(loggerContext);
        writerByPid = new ConcurrentHashMap<String, LogWriter>();
        writerByFileName = new ConcurrentHashMap<String, LogWriter>();
        configByPid = new ConcurrentHashMap<String, LogConfig>();
        configByCategory = new ConcurrentHashMap<String, LogConfig>();

        this.rootDir = new File(rootDir);
        setDefaultConfiguration(getBundleConfiguration(bundleContext));
        this.configAdminSupport = new ConfigAdminSupport(bundleContext,this);
    }

    /**
     * Sets and applies the default configuration used by the
     * {@link #updateGlobalConfiguration(java.util.Dictionary)} method if no configuration
     * is supplied.
     */
    public void setDefaultConfiguration(
            Dictionary<String, String> defaultConfiguration) {
        this.defaultConfiguration = defaultConfiguration;
        try {
            updateGlobalConfiguration(defaultConfiguration);
        } catch (ConfigurationException ce) {
            internalFailure(ce.getMessage(), ce);
        }
    }

    /**
     * Shuts this configuration manager down by dropping all references to
     * existing configurations, dropping all stored loggers and closing all log
     * writers.
     * <p>
     * After this methods is called, this instance should not be used again.
     */
    public void close() {
        configAdminSupport.shutdown();

        writerByPid.clear();
        writerByFileName.clear();
        configByPid.clear();
        configByCategory.clear();

        this.defaultConfiguration = null;
    }

    // ---------- SlingLogPanel support

    LogWriter getLogWriter(String logWriterName) {
        LogWriter lw = writerByFileName.get(logWriterName);
        if(lw == null){
            lw = createImplicitWriter(logWriterName);
        }
        return lw;
    }

    LoggerContext getLoggerContext(){
        return loggerContext;
    }


    // ---------- Logback reset listener

    public void onReset(LoggerContext context) {
        Map<String,Appender> appendersByName = new HashMap<String, Appender>();
        Map<Appender,LoggerSpecificEncoder> encoders = new HashMap<Appender, LoggerSpecificEncoder>();
        for(LogConfig config : getLogConfigs()){
            Appender appender = null;
            if(config.isAppenderDefined()){
                LogWriter lw = config.getLogWriter();
                appender = appendersByName.get(lw.getFileName());
                if(appender == null){
                    LoggerSpecificEncoder encoder = new LoggerSpecificEncoder(getDefaultLayout());
                    appender = lw.createAppender(loggerContext,encoder);
                    encoders.put(appender,encoder);
                }
                encoders.get(appender).addLogConfig(config);
            }

            for(String category : config.getCategories()){
                ch.qos.logback.classic.Logger logger = loggerContext.getLogger(category);
                logger.setLevel(config.getLogLevel());

                logger.addAppender(appender);
            }
        }
    }



    // ---------- Configuration support

    public void updateGlobalConfiguration(
            Dictionary<String, String> configuration)
            throws ConfigurationException {
        // fallback to start default settings when the config is deleted
        if (configuration == null) {
            configuration = defaultConfiguration;
        }

        // set the logger name to a special value to indicate the global
        // (ROOT) logger setting (SLING-529)
        configuration.put(LogConfigManager.LOG_LOGGERS, Logger.ROOT_LOGGER_NAME);

        // update the default log writer and logger configuration
        updateLogWriter(LogConfigManager.PID, configuration);
        updateLoggerConfiguration(LogConfigManager.PID, configuration);
    }

    /**
     * Updates or removes the log writer configuration identified by the
     * <code>pid</code>. In case of log writer removal, any logger
     * configuration referring to the removed log writer is modified to now log
     * to the default log writer.
     * <p>
     * The configuration object is expected to contain the following properties:
     * <dl>
     * <dt>{@link java.util.logging.LogManager#LOG_FILE}</dt>
     * <dd>The relative of absolute path/name of the file to log to. If this
     * property is missing or an empty string, the writer writes to standard
     * output</dd>
     * <dt>{@link java.util.logging.LogManager#LOG_FILE_SIZE}</dt>
     * <dd>The maximum size of the log file to write before rotating the log
     * file. This property must be a number of be convertible to a number. The
     * actual value may also be suffixed by a size indicator <code>k</code>,
     * <code>kb</code>, <code>m</code>, <code>mb</code>, <code>g</code>
     * or <code>gb</code> representing the respective factors of kilo, mega
     * and giga.If this property is missing or cannot be converted to a number,
     * the default value {@link java.util.logging.LogManager#LOG_FILE_SIZE_DEFAULT} is assumed. If
     * the writer writes standard output this property is ignored.</dd>
     * <dt>{@link java.util.logging.LogManager#LOG_FILE_NUMBER}</dt>
     * <dd>The maximum number of rotated log files to keep. This property must
     * be a number of be convertible to a number. If this property is missing or
     * cannot be converted to a number, the default value
     * {@link java.util.logging.LogManager#LOG_FILE_NUMBER_DEFAULT} is assumed. If the writer
     * writes standard output this property is ignored.</dd>
     * </dl>
     *
     * @param pid The identifier of the log writer to update or remove
     * @param configuration New configuration setting for the log writer or
     *            <code>null</code> to indicate to remove the log writer.
     * @throws ConfigurationException If another log writer already exists for
     *             the same file as configured for the given log writer or if
     *             configuring the log writer fails.
     */
    public void updateLogWriter(String pid, Dictionary<?, ?> configuration)
            throws ConfigurationException {

        if (configuration != null) {
            LogWriter oldWriter = writerByPid.get(pid);

            // get the log file parameter and normalize empty string to null
            String logFileName = (String) configuration.get(LogConfigManager.LOG_FILE);
            if (logFileName != null && logFileName.trim().length() == 0) {
                logFileName = null;
                //TODO Null logFileName means configuring the ConsoleAppender. Need to be
                //Handled separately
            }

            // if we have a file name, make it absolute and correct for our
            // environment and verify there is no other writer already existing
            // for the same file
            if (logFileName != null) {

                // ensure absolute path
                logFileName = getAbsoluteLogFile(logFileName);

                // ensure unique configuration of the log writer
                LogWriter existingWriterByFileName = writerByFileName.get(logFileName);
                if (existingWriterByFileName != null
                        && (oldWriter != null && !existingWriterByFileName.getConfigurationPID().equals(pid))) {

                    // this file is already configured by another LOG_PID
                    throw new ConfigurationException(LogConfigManager.LOG_FILE,
                            "LogFile " + logFileName
                                    + " already configured by configuration "
                                    + existingWriterByFileName.getConfigurationPID());
                }
            }

            // get number of files and ensure minimum and default
            Object fileNumProp = configuration.get(LogConfigManager.LOG_FILE_NUMBER);
            int fileNum = -1;
            if (fileNumProp instanceof Number) {
                fileNum = ((Number) fileNumProp).intValue();
            } else if (fileNumProp != null) {
                try {
                    fileNum = Integer.parseInt(fileNumProp.toString());
                } catch (NumberFormatException nfe) {
                    // don't care
                }
            }

            // get the log file size
            Object fileSizeProp = configuration.get(LogConfigManager.LOG_FILE_SIZE);
            String fileSize = null;
            if (fileSizeProp != null) {
                fileSize = fileSizeProp.toString();
            }

            LogWriter newWriter = new LogWriter(pid,logFileName,fileNum,fileSize);
            writerByFileName.remove(oldWriter.getFileName());
            writerByFileName.put(newWriter.getFileName(), newWriter);
            writerByPid.put(newWriter.getConfigurationPID(),newWriter);

        } else {

            final LogWriter logWriter = writerByPid.remove(pid);

            if (logWriter != null) {
                writerByFileName.remove(logWriter.getFileName());
            }
        }

        logbackManager.configChanged();
    }

    /**
     * Updates or removes the logger configuration indicated by the given
     * <code>pid</code>. If the case of modified categories or removal of the
     * logger configuration, existing loggers will be modified to reflect the
     * correct logger configurations available.
     * <p>
     * The configuration object is expected to contain the following properties:
     * <dl>
     * <dt>{@link LogConfigManager#LOG_PATTERN}</dt>
     * <dd>The <code>MessageFormat</code> pattern to apply to format the log
     * message before writing it to the log writer. If this property is missing
     * or the empty string the default pattern
     * {@link LogConfigManager#LOG_PATTERN_DEFAULT} is used.</dd>
     * <dt>{@link LogConfigManager#LOG_LEVEL}</dt>
     * <dd>The log level to use for log message limitation. The supported
     * values are <code>trace</code>, <code>debug</code>,
     * <code>info</code>, <code>warn</code> and <code>error</code>. Case
     * does not matter. If this property is missing a
     * <code>ConfigurationException</code> is thrown and this logger
     * configuration is not used.</dd>
     * <dt>{@link LogConfigManager#LOG_LOGGERS}</dt>
     * <dd>The logger names to which this configuration applies. As logger
     * names form a hierarchy like Java packages, the listed names also apply to
     * "child names" unless more specific configuration applies for such
     * children. This property may be a single string, an array of strings or a
     * collection of strings. Each string may itself be a comma-separated list of
     * logger names. If this property is missing a
     * <code>ConfigurationException</code> is thrown.</dd>
     * <dt>{@link LogConfigManager#LOG_FILE}</dt>
     * <dd>The name of the log writer to use. This may be the name of a log
     * file configured for any log writer or it may be the configuration PID of
     * such a writer. If this property is missing or empty or does not refer to
     * an existing log writer configuration, the default log writer is used.</dd>
     *
     * @param pid The name of the configuration to update or remove.
     * @param configuration The configuration object.
     * @throws ConfigurationException If the log level and logger names
     *             properties are not configured for the given configuration.
     */
    public void updateLoggerConfiguration(String pid,
            Dictionary<?, ?> configuration) throws ConfigurationException {

        if (configuration != null) {

            String pattern = (String) configuration.get(LogConfigManager.LOG_PATTERN);
            String level = (String) configuration.get(LogConfigManager.LOG_LEVEL);
            String file = (String) configuration.get(LogConfigManager.LOG_FILE);
            Set<String> categories = toCategoryList(configuration.get(LogConfigManager.LOG_LOGGERS));

            // verify categories
            if (categories == null) {
                throw new ConfigurationException(LogConfigManager.LOG_LOGGERS,
                    "Missing categories in configuration " + pid);
            }

            // verify no other configuration has any of the categories
            for (String cat : categories) {
                LogConfig cfg = configByCategory.get(cat);
                if (cfg != null && !pid.equals(cfg.getConfigPid())) {
                    throw new ConfigurationException(LogConfigManager.LOG_LOGGERS,
                        "Category " + cat
                            + " already defined by configuration " + pid);
                }
            }

            // verify log level
            if (level == null) {
                throw new ConfigurationException(LogConfigManager.LOG_LEVEL,
                    "Value required");
            }
            // TODO: support numeric levels !
            Level logLevel = Level.toLevel(level);
            if (logLevel == null) {
                throw new ConfigurationException(LogConfigManager.LOG_LEVEL,
                    "Unsupported value: " + level);
            }

            // verify pattern
            if (pattern == null || pattern.length() == 0) {
                pattern = LogConfigManager.LOG_PATTERN_DEFAULT;
            }

            //TODO: Verify fileName or it can be null meaning that user just wants to
            //control the log level

            // create or modify existing configuration object
            LogConfig newConfig = new LogConfig(this, pattern, categories, logLevel, file, pid);
            LogConfig oldConfig = configByPid.get(pid);
            if(oldConfig != null){
                configByCategory.keySet().removeAll(oldConfig.getCategories());
            }

            // relink categories
            for (String cat : categories) {
                configByCategory.put(cat, newConfig);
            }

            configByPid.put(pid,newConfig);

        } else {

            // configuration deleted if null

            // remove configuration from pid list
            LogConfig config = configByPid.remove(pid);

            if (config != null) {
                // remove all configured categories
                configByCategory.keySet().removeAll(config.getCategories());
            }

        }
        logbackManager.configChanged();
    }

    // ---------- ManagedService interface -------------------------------------

    private Dictionary<String, String> getBundleConfiguration(
            BundleContext bundleContext) {
        Dictionary<String, String> config = new Hashtable<String, String>();

        final String[] props = { LOG_LEVEL, LOG_LEVEL, LOG_FILE,
                LOG_FILE_NUMBER, LOG_FILE_SIZE, LOG_PATTERN };
        for (String prop : props) {
            String value = bundleContext.getProperty(prop);
            if (value != null) {
                config.put(prop, value);
            }
        }

        // ensure sensible default values for required configuration field(s)
        if (config.get(LOG_LEVEL) == null) {
            config.put(LOG_LEVEL, LOG_LEVEL_DEFAULT);
        }

        return config;
    }

    // ---------- Internal helpers ---------------------------------------------

    private LogWriter createImplicitWriter(String logWriterName) {
        LogWriter defaultWriter = getDefaultWriter();
        if(defaultWriter == null){
            throw new IllegalStateException("Default logger configuration must have been configured by now");
        }
        return new LogWriter(logWriterName,defaultWriter.getLogNumber(),defaultWriter.getLogRotation());
    }

    private LogWriter getDefaultWriter() {
        return writerByPid.get(LogConfigManager.PID);
    }

    private LogConfig getDefaultConfig() {
        return configByPid.get(LogConfigManager.PID);
    }

    private Layout<ILoggingEvent> getDefaultLayout(){
        return getDefaultConfig().createLayout();
    }

    private Iterable<LogConfig> getLogConfigs() {
        return configByPid.values();
    }

    /**
     * Returns the <code>logFileName</code> argument converted into an
     * absolute path name. If <code>logFileName</code> is already absolute it
     * is returned unmodified. Otherwise it is made absolute by resolving it
     * relative to the root directory set on this instance by the
     * {@link #setRoot(String)} method.
     *
     * @throws NullPointerException if <code>logFileName</code> is
     *             <code>null</code>.
     */
    private String getAbsoluteLogFile(String logFileName) {
        // ensure proper separator in the path (esp. for systems, which do
        // not use "slash" as a separator, e.g Windows)
        logFileName = logFileName.replace('/', File.separatorChar);

        // create a file instance and check whether this is absolute. If not
        // create a new absolute file instance with the root dir and get
        // the absolute path name from that
        File logFile = new File(logFileName);
        if (!logFile.isAbsolute()) {
            logFile = new File(rootDir, logFileName);
            logFileName = logFile.getAbsolutePath();
        }

        // return the correct log file name
        return logFileName;
    }

    /**
     * Decomposes the <code>loggers</code> configuration object into a set of
     * logger names. The <code>loggers</code> object may be a single string,
     * an array of strings or a collection of strings. Each string may in turn be a
     * comma-separated list of strings. Each entry makes up an entry in the
     * resulting set.
     *
     * @param loggers The configuration object to be decomposed. If this is
     *            <code>null</code>, <code>null</code> is returned
     *            immediately
     * @return The set of logger names provided by the <code>loggers</code>
     *         object or <code>null</code> if the <code>loggers</code>
     *         object itself is <code>null</code>.
     */
    private Set<String> toCategoryList(Object loggers) {

        // quick exit if there is no configuration
        if (loggers == null) {
            return null;
        }

        // prepare set of names (used already in case loggers == ROOT)
        Set<String> loggerNames = new HashSet<String>();

        // in case of the special setting ROOT, return a set of just the
        // root logger name (SLING-529)
        if (loggers == ROOT) {
            loggerNames.add(ROOT);
            return loggerNames;
        }

        // convert the loggers object to an array
        Object[] loggersArray;
        if (loggers.getClass().isArray()) {
            loggersArray = (Object[]) loggers;
        } else if (loggers instanceof Collection<?>) {
            loggersArray = ((Collection<?>) loggers).toArray();
        } else {
            loggersArray = new Object[] { loggers };
        }

        // convert the array of potentially comma-separated logger names
        // into the set of logger names
        for (Object loggerObject : loggersArray) {
            if (loggerObject != null) {
                String[] splitLoggers = loggerObject.toString().split(",");
                for (String logger : splitLoggers) {
                    logger = logger.trim();
                    if (logger.length() > 0) {
                        loggerNames.add(logger);
                    }
                }
            }
        }

        // return those names
        return loggerNames;
    }


}
