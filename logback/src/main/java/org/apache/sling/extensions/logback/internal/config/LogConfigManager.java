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
package org.apache.sling.extensions.logback.internal.config;

import java.io.File;
import java.util.Collection;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import org.apache.sling.commons.log.internal.LogbackLogManager;

public class LogConfigManager {

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
    private final LogConfig defaultLoggerConfig;

    // the default writer configuration set up by the constructor and managed
    // by the global logger configuration
    private final LogWriter defaultWriter;

    // the root folder to make relative writer paths absolute
    private File rootDir;

    // global default configuration (from BundleContext properties)
    private Dictionary<String, String> defaultConfiguration;

    /**
     * Logs a message an optional stack trace to error output. This method is
     * used by the logging system in case of errors writing to the correct
     * logging output.
     */
    public static void internalFailure(String message, Throwable t) {
        System.err.println(message);
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    /**
     * Sets up this log configuration manager by creating the default writers
     * and logger configuration
     */
    public LogConfigManager(LoggerContext context) {
        this.loggerContext = context;
        writerByPid = new ConcurrentHashMap<String, LogWriter>();
        writerByFileName = new ConcurrentHashMap<String, LogWriter>();
//        appenderByKey = new ConcurrentHashMap<AppenderKey, Appender<ILoggingEvent>>();
        configByPid = new ConcurrentHashMap<String, LogConfig>();
        configByCategory = new ConcurrentHashMap<String, LogConfig>();
//        loggersByCategory = new ConcurrentHashMap<String, SoftReference<SlingLogger>>();


        this.defaultWriter = configureLogWriter(null, LogbackLogManager.PID, "", -1, null);
        this.defaultLoggerConfig = new LogConfig(LogbackLogManager.PID, LogbackLogManager.LOG_PATTERN_DEFAULT, null, null, null);

        // set up the default configuration using the default logger
        // writing at INFO level to start with
        Set<String> defaultCategories = new HashSet<String>();
        defaultCategories.add(ROOT);
    }

    /**
     * Sets the root (folder) to be used to make relative paths absolute.
     *
     * @param root The path to the folder to use as a reference
     */
    public void setRoot(String root) {
        rootDir = new File((root == null) ? "" : root).getAbsoluteFile();
    }

    /**
     * Sets the Logback LoggerContext to access and configure appenders
     * and loggers
     *
     * @param loggerContext The Logback {@code LoggerContext} to configure
     */
    public void setLoggerContext(LoggerContext loggerContext) {
        this.loggerContext = loggerContext;

        this.defaultWriter.setContext(loggerContext);

        // reset logging to our null-default
        this.loggerContext.reset();

        // configure the default writer to write to stdout (for now)
        // and register for PID only
        PatternLayoutEncoder pl = new PatternLayoutEncoder();
        pl.setContext(this.loggerContext);
        pl.setPattern(LogbackLogManager.LOG_PATTERN_DEFAULT);
        pl.start();

        ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<ILoggingEvent>();
        console.setContext(this.loggerContext);
        console.setName(LogbackLogManager.PID);
        console.setEncoder(pl);
        console.start();

        this.loggerContext.getLogger(ROOT).addAppender(this.defaultWriter.createAppender(LogbackLogManager.LOG_PATTERN_DEFAULT));

//        this.appenderByKey.put(new AppenderKey("", LogbackLogManager.LOG_PATTERN_DEFAULT), console);
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
        if (this.loggerContext != null) {
            this.loggerContext.reset();
        }

        writerByPid.clear();
        writerByFileName.clear();
        configByPid.clear();
        configByCategory.clear();

        // reset fields
        this.loggerContext = null;
        this.rootDir = null;
        this.defaultConfiguration = null;
    }

    // ---------- SlingLogPanel support

    /**
     * Return configured {@link SlingLoggerConfig} instances as an iterator.
     */
    Iterator<LogConfig> getSlingLoggerConfigs() {
        return configByPid.values().iterator();
    }

    /**
     * Return configured and implicit {@link SlingLoggerWriter} instances as
     * an iterator.
     */
    Iterator<LogWriter> getSlingLoggerWriters() {
        return internalGetSlingLoggerWriters().iterator();
    }

    /**
     * Returns the number of logger configurations active in the system
     */
    int getNumSlingLoggerConfigs() {
        return configByPid.size();
    }

    /**
     * Returns the number of logger writers active in the system
     */
    int getNumSlingLogWriters() {
        return internalGetSlingLoggerWriters().size();
    }

    /**
     * Returns the number of currently user logger categories
     */
    int getNumLoggers() {
        return this.loggerContext.getLoggerList().size();
    }

    /**
     * Internal method returns the collection of explicitly configured and
     * implicitly defined logger writers.
     */
    private Collection<LogWriter> internalGetSlingLoggerWriters() {
        // configured writers
        Collection<LogWriter> writers = new HashSet<LogWriter>(
            writerByPid.values());

        // add implicit writers
        for (LogWriter slw : writerByFileName.values()) {
            if (slw.getConfigurationPID() == null) {
                writers.add(slw);
            }
        }

        return writers;
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
        configuration.put(LogbackLogManager.LOG_LOGGERS, LogConfigManager.ROOT);

        // update the default log writer and logger configuration
        updateLogWriter(LogbackLogManager.PID, configuration);
        updateLoggerConfiguration(LogbackLogManager.PID, configuration);
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
            LogWriter slw = writerByPid.get(pid);

            // get the log file parameter and normalize empty string to null
            String logFileName = (String) configuration.get(LogbackLogManager.LOG_FILE);
            if (logFileName != null && logFileName.trim().length() == 0) {
                logFileName = null;
            }

            // if we have a file name, make it absolute and correct for our
            // environment and verify there is no other writer already existing
            // for the same file
            if (logFileName != null) {

                // ensure absolute path
                logFileName = getAbsoluteLogFile(logFileName);

                // ensure unique configuration of the log writer
                LogWriter existingWriter = writerByFileName.get(logFileName);
                if (existingWriter != null) {
                    if (slw == null) {

                        // this is an implicit writer being configured now
                        slw = existingWriter;
                        slw.setConfigurationPID(pid);
                        writerByPid.put(pid, slw);

                    } else if (!existingWriter.getConfigurationPID().equals(pid)) {

                        // this file is already configured by another LOG_PID
                        throw new ConfigurationException(LogbackLogManager.LOG_FILE,
                            "LogFile " + logFileName
                                + " already configured by configuration "
                                + existingWriter.getConfigurationPID());
                    }
                }
            }

            // get number of files and ensure minimum and default
            Object fileNumProp = configuration.get(LogbackLogManager.LOG_FILE_NUMBER);
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
            Object fileSizeProp = configuration.get(LogbackLogManager.LOG_FILE_SIZE);
            String fileSize = null;
            if (fileSizeProp != null) {
                fileSize = fileSizeProp.toString();
            }

            if (configureLogWriter(slw, pid, logFileName, fileNum, fileSize) == null) {
                throw new ConfigurationException(LogbackLogManager.LOG_FILE,
                    "Cannot create writer for log file " + logFileName);
            }

        } else {

            final LogWriter logWriter = writerByPid.remove(pid);
            if (logWriter != null) {

                // make the writer implicit
                logWriter.setConfigurationPID(null);

                // close if unused, otherwise reconfigure to default values
                closeIfUnused(logWriter, true);
            }
        }
    }

    /**
     * Updates or removes the logger configuration indicated by the given
     * <code>pid</code>. If the case of modified categories or removal of the
     * logger configuration, existing loggers will be modified to reflect the
     * correct logger configurations available.
     * <p>
     * The configuration object is expected to contain the following properties:
     * <dl>
     * <dt>{@link java.util.logging.LogManager#LOG_PATTERN}</dt>
     * <dd>The <code>MessageFormat</code> pattern to apply to format the log
     * message before writing it to the log writer. If this property is missing
     * or the empty string the default pattern
     * {@link java.util.logging.LogManager#LOG_PATTERN_DEFAULT} is used.</dd>
     * <dt>{@link java.util.logging.LogManager#LOG_LEVEL}</dt>
     * <dd>The log level to use for log message limitation. The supported
     * values are <code>trace</code>, <code>debug</code>,
     * <code>info</code>, <code>warn</code> and <code>error</code>. Case
     * does not matter. If this property is missing a
     * <code>ConfigurationException</code> is thrown and this logger
     * configuration is not used.</dd>
     * <dt>{@link java.util.logging.LogManager#LOG_LOGGERS}</dt>
     * <dd>The logger names to which this configuration applies. As logger
     * names form a hierarchy like Java packages, the listed names also apply to
     * "child names" unless more specific configuration applies for such
     * children. This property may be a single string, an array of strings or a
     * collection of strings. Each string may itself be a comma-separated list of
     * logger names. If this property is missing a
     * <code>ConfigurationException</code> is thrown.</dd>
     * <dt>{@link java.util.logging.LogManager#LOG_FILE}</dt>
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

        // assume we have to reconfigure the loggers
        boolean reconfigureLoggers = true;

        if (configuration != null) {

            String pattern = (String) configuration.get(LogbackLogManager.LOG_PATTERN);
            String level = (String) configuration.get(LogbackLogManager.LOG_LEVEL);
            Set<String> files = toCategoryList(configuration.get(LogbackLogManager.LOG_FILE));
            Set<String> categories = toCategoryList(configuration.get(LogbackLogManager.LOG_LOGGERS));

            // verify categories
            if (categories == null) {
                throw new ConfigurationException(LogbackLogManager.LOG_LOGGERS,
                    "Missing categories in configuration " + pid);
            }

            // verify no other configuration has any of the categories
            for (String cat : categories) {
                LogConfig cfg = configByCategory.get(cat);
                if (cfg != null && !pid.equals(cfg.getConfigPid())) {
                    throw new ConfigurationException(LogbackLogManager.LOG_LOGGERS,
                        "Category " + cat
                            + " already defined by configuration " + pid);
                }
            }

            // verify log level
            if (level == null) {
                throw new ConfigurationException(LogbackLogManager.LOG_LEVEL,
                    "Value required");
            }
            // TODO: support numeric levels !
            Level logLevel = Level.toLevel(level);
            if (logLevel == null) {
                throw new ConfigurationException(LogbackLogManager.LOG_LEVEL,
                    "Unsupported value: " + level);
            }

            // verify pattern
            if (pattern == null || pattern.length() == 0) {
                pattern = LogbackLogManager.LOG_PATTERN_DEFAULT;
            }

            HashSet<String> logWriterNames = new HashSet<String>();
            for (String file : files) {
                LogWriter writer = getLogWriter(file, true);
                logWriterNames.add(writer.getFileName());
            }

            // create or modify existing configuration object
            LogConfig config = configByPid.get(pid);
            if (config == null) {

                // create and store new configuration
                config = new LogConfig(pid, pattern, categories, logLevel, logWriterNames);
                configByPid.put(pid, config);

            } else {

                // remove category to configuration mappings
                final Set<String> oldCategories = config.getCategories();

                // check whether the log writer is to be changed
                final Set<String> oldLogWriterNames = config.getLogWriterNames();

                // reconfigure the configuration
                config.configure(pattern, categories, logLevel, logWriterNames);

                if (categories.equals(oldCategories)) {

                    // no need to change category registrations, clear them
                    // also no need to reconfigure the loggers
                    categories.clear();
                    reconfigureLoggers = false; // indeed ??

                } else {

                    // remove the old categories if different from the new ones
                    configByCategory.keySet().removeAll(oldCategories);

                }

                // close the old log writer if replaced and not used any more
                if (logWriterNames.equals(oldLogWriterNames)) {

                    // no need to change appenderkey registrations, clear them
                    // also no need to reconfigure the loggers
                    logWriterNames.clear();
                    reconfigureLoggers = false; // indeed ??

                } else {

                    // get all appenders not used by this config any more
                    oldLogWriterNames.removeAll(logWriterNames);
                    for (String logWriterName : oldLogWriterNames) {
                        LogWriter writer = getLogWriter(logWriterName, false);
                        if (writer != null) {
                            closeIfUnused(writer, false);
                        }
                    }
                }
            }

            // relink categories
            for (String cat : categories) {
                configByCategory.put(cat, config);
            }

        } else {

            // configuration deleted if null

            // remove configuration from pid list
            LogConfig config = configByPid.remove(pid);

            if (config != null) {
                // remove all configured categories
                configByCategory.keySet().removeAll(config.getCategories());

                // close the writer if unused (and unconfigured)
                for (String logWriterName : config.getLogWriterNames()) {
                    LogWriter writer = getLogWriter(logWriterName, false);
                    if (writer != null) {
                        closeIfUnused(writer, false);
                    }
                }
            }

        }

        // reconfigure existing loggers
        if (reconfigureLoggers) {
            reconfigureLoggers();
        }
    }

    // ---------- Internal helpers ---------------------------------------------

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
     * Reconfigures all loggers such that each logger is supplied with the
     * {@link SlingLoggerConfig} most appropriate to its name. If a registered
     * logger is not used any more, it is removed from the list.
     */
    private void reconfigureLoggers() {
        // assign correct logger configs to all existing/known loggers
        List<ch.qos.logback.classic.Logger> loggers= this.loggerContext.getLoggerList();
        for (ch.qos.logback.classic.Logger logger : loggers) {
            LogConfig config = this.configByCategory.get(logger.getName());
            if (config == null) {
                logger.setLevel(null);
            } else {
                logger.setLevel(config.getLogLevel());
            }

            // appenders of the Logback logger
            Iterator<Appender<ILoggingEvent>> aii = logger.iteratorForAppenders();
            HashSet<Appender<ILoggingEvent>> appenders = new HashSet<Appender<ILoggingEvent>>();
            if (aii != null) {
                while (aii.hasNext()) {
                    appenders.add(aii.next());
                }
            }

            // appenders of the configuration
            for (String logWriterName : config.getLogWriterNames()) {
                LogWriter writer = getLogWriter(logWriterName, false);
                if (writer != null) {
                    for (Appender<ILoggingEvent> appender : writer.getAppenders()) {
                    }
                }
            }

            // elements still in appenders are appenders to remove
            for (Appender<ILoggingEvent> appender: appenders) {
                if (!configAppenders.contains(appender)) {
                    logger.detachAppender(appender);
                    closeIfUnused(appender);
                }
            }

            // elements still in appenderkeys are appenders to add
            for (AppenderKey appenderKey : appenderKeys) {
                logger.addAppender(getOrCreateAppender(appenderKey));
            }
        }
    }

    /**
     * Returns a {@link SlingLoggerConfig} instance applicable to the given
     * <code>logger</code> name. This is the instance applicable to a longest
     * match log. If no such instance exists, the default logger configuration
     * is returned.
     */
    private LogConfig getLoggerConfig(String logger) {
        for (;;) {
            LogConfig config = configByCategory.get(logger);
            if (config != null) {
                return config;
            }

            if (logger.length() == 0) {
                break;
            }

            int dot = logger.lastIndexOf('.');
            if (dot < 0) {
                logger = ROOT;
            } else {
                logger = logger.substring(0, dot);
            }
        }

        return defaultLoggerConfig;
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

        // conver the array of potentially comma-separated logger names
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

    /**
     * Configures and returns a {@link SlingLoggerWriter}. If the
     * <code>pid</code> is not <code>null</code> the writer is also added to the
     * by pid map. If the <code>fileName</code> is not <code>null</code> the
     * writer is also added to the file name map.
     *
     * @param writer The {@link SlingLoggerWriter} to configure. If this is
     *            <code>null</code> a new instance is created.
     * @param pid The configuration PID to set on the <code>writer</code>. This
     *            may be <code>null</code> to indicate that the logger writer is
     *            not configured by any configuration.
     * @param fileName The name of the file to log to.
     * @param fileNum The number of log files to keep (if rotating by size) or
     *            -1 to assume the default (
     *            {@link java.util.logging.LogManager#LOG_FILE_NUMBER_DEFAULT}).
     * @param threshold The log rotation threashold (size or data/time format
     *            pattern or <code>null</code> to assume the default (
     *            {@link java.util.logging.LogManager#LOG_FILE_SIZE_DEFAULT}).
     * @return The {@link SlingLoggerWriter} or <code>null</code> if an error
     *         occurrs configuring the writer.
     */
    private LogWriter configureLogWriter(LogWriter writer,
            String pid, String fileName, int fileNum, String threshold) {

        // create the writer instance if it is new
        if (writer == null) {
            writer = new LogWriter();
            writer.setContext(this.loggerContext);
        }
        writer.setConfigurationPID(pid);
        writer.setFileName(fileName);
        writer.setLogNumber(fileNum);
        writer.setLogRotation(threshold);

        // add to maps
        if (pid != null) {
            writerByPid.put(pid, writer);
        }
        writerByFileName.put(writer.getFileName(), writer);

        // everything set and done
        return writer;
    }

    private Appender<ILoggingEvent> getOrCreateAppender(AppenderKey appenderKey) {
        LogWriter writer = getLogWriter(appenderKey.getFileName(), true);
        return writer.createAppender(appenderKey.getPattern());
    }

    private LogWriter getLogWriter(final String file, final boolean create) {
        LogWriter writer = writerByPid.get(file);
        if (writer == null) {
            writer = writerByFileName.get(file);
            if (writer == null) {
                final String absoluteFile = getAbsoluteLogFile(file);
                writer = writerByFileName.get(absoluteFile);
                if (writer == null && create) {
                    writer = configureLogWriter(null, null, absoluteFile, -1, null);
                }
            }
        }
        return writer;
    }

    /**
     * Closes or resets the given <code>logWriter</code> if it is not referred
     * to by any logger config or writer configuration.
     *
     * @param logWriter The {@link SlingLoggerWriter} to close or (optionally)
     *            reconfigure.
     * @param reset Whether the log writer should be reset to default values if
     *            it is still referred to by any logger configuration.
     */
    private void closeIfUnused(Appender<ILoggingEvent> appender) {
        for (ch.qos.logback.classic.Logger logger : this.loggerContext.getLoggerList()) {
            if (logger.isAttached(appender)) {
                return;
            }
        }

        // no logger has the appender, so stop and remove
        appender.stop();

        // TODO: Consider optimizing by having an AppenderKey.fromString(String) method
        for (Iterator<Entry<AppenderKey, Appender<ILoggingEvent>>> ei = appenderByKey.entrySet().iterator(); ei.hasNext(); ) {
            Entry<AppenderKey, Appender<ILoggingEvent>> entry = ei.next();
            if (entry.getValue() == appender) {
                ei.remove();
            }
        }
    }

    private void closeIfUnused(LogWriter logWriter, boolean reset) {

        // The log writer is based on configuration, don't touch
        if (logWriter.getConfigurationPID() != null) {
            return;
        }

        // "Implicit" writer : check for references
        for (LogConfig config : configByPid.values()) {
            if (config.getLogWriterNames().contains(logWriter.getFileName())) {
                // optionally reconfigure to default values
                if (reset) {
                    logWriter.setLogNumber(LogbackLogManager.LOG_FILE_NUMBER_DEFAULT);
                    logWriter.setLogRotation(LogbackLogManager.LOG_FILE_SIZE_DEFAULT);

                    // TODO: Apply this configuration to the appender(s)
                }

                // done here...
                return;
            }
        }
        // invariant: writer is not used and not configured any more

        // remove from the writer file name map
        writerByFileName.remove(logWriter.getFileName());

        // close it to clean up
        logWriter.close();
    }
}
