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

import java.text.MessageFormat;
import java.util.Set;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;

public class LogConfig {

    private final String configPid;

    private final Set<String> categories;

    private final Level logLevel;

    private final String pattern;

    private final String logWriterName;

    private final LogConfigManager logConfigManager;

    LogConfig(LogConfigManager logConfigManager, final String pattern,
              Set<String> categories, Level logLevel, String logWriterName, String configPid) {
        this.logConfigManager = logConfigManager;
        this.configPid = configPid;
        this.pattern = pattern;
        this.categories = categories;
        this.logLevel = logLevel;
        this.logWriterName = logWriterName;
    }

    public String getConfigPid() {
        return configPid;
    }

    public Set<String> getCategories() {
        return categories;
    }

    public Level getLogLevel() {
        return logLevel;
    }

    public String getPattern() {
        return pattern;
    }

    public String getLogWriterName() {
        return logWriterName;
    }

    public boolean isAppenderDefined(){
        return logWriterName != null;
    }

    public LogWriter getLogWriter(){
        return logConfigManager.getLogWriter(logWriterName);
    }

    public PatternLayout createLayout(){
        Pattern date = Pattern.compile("\\{0,date,(.+)\\}");
        String logBackPattern = date.matcher(getPattern()).replaceAll("d{$1}");

        logBackPattern = MessageFormat.format(logBackPattern, "zero", "%marker", "%thread", "%logger", "%level",
                "%message") + "%n";

        PatternLayout pl = new PatternLayout();
        pl.setPattern(logBackPattern);
        pl.setOutputPatternAsHeader(false);
        pl.setContext(logConfigManager.getLoggerContext());
        pl.start();
        return pl;
    }
}
