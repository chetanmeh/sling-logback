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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import org.apache.sling.extensions.logback.internal.util.SlingRollingFileAppender;
import org.osgi.framework.Constants;

import static org.apache.sling.extensions.logback.internal.AppenderTracker.AppenderInfo;

/**
 * The <code>SlingLogPanel</code> is a Felix Web Console plugin to display the
 * current active log bundle configuration.
 * <p>
 * In future revisions of this plugin, the configuration may probably even
 * be modified through this panel.
 */
public class SlingLogPanel extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final LogbackManager logbackManager;

    public SlingLogPanel(final LogbackManager logbackManager) {
        this.logbackManager = logbackManager;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        final PrintWriter pw = resp.getWriter();

        final String consoleAppRoot = (String) req.getAttribute("felix.webconsole.appRoot");
        final String cfgColTitle = (consoleAppRoot == null) ? "PID" : "Configuration";

        final List<Logger> loggers = logbackManager.getLoggerContext().getLoggerList();
        final LoggerStateContext ctx = determineLoggerState(loggers);
        pw.printf(
            "<p class='statline'>Log Service Stats: %d categories, %d configuration(s), %d appenders(s), %d OSGi appenders(s), %d Logback Appenders(s)</p>%n",
                loggers.size(),
                ctx.getNumofSlingLogConfig(),
                ctx.getNumofSlingLogWriters(),
                ctx.getNumOfDynamicAppenders(),
                ctx.getNumOfLogbackAppenders()
        );

        pw.println("<div class='table'>");

        pw.println("<div class='ui-widget-header ui-corner-top buttonGroup'>Logger</div>");

        pw.println("<table class='nicetable ui-widget'>");

        pw.println("<thead class='ui-widget-header'>");
        pw.println("<tr>");
        pw.println("<th>Log Level</th>");
        pw.println("<th>Name</th>");
        pw.println("<th>Appender</th>");
        //pw.println("<th>" + cfgColTitle + "</th>");
        pw.println("</tr>");
        pw.println("</thead>");
        pw.println("<tbody class='ui-widget-content'>");

        for(LoggerInfo li : ctx.loggerInfos){
            pw.println("<tr>");
            pw.println("<td>" + li.logger.getLevel() + "</td>");
            pw.println("<td>" + li.logger.getName() + "</td>");

            pw.println("<td>");
            pw.println("<ul>");
            Iterator<Appender<ILoggingEvent>> itr = li.logger.iteratorForAppenders();
            while(itr.hasNext()) {
                Appender<ILoggingEvent> a = itr.next();
                pw.print("<li>");
                pw.println(getName(a));
                pw.print("</li>");
            }
            pw.println("</ul>");
            pw.println("</td>");


            pw.println("</tr>");
        }



        pw.println("</tbody>");
        pw.println("</table>");
        pw.println("</div>");

        pw.println("<div class='table'>");

        pw.println("<div class='ui-widget-header ui-corner-top buttonGroup'>Appender</div>");

        pw.println("<table class='nicetable ui-widget'>");

        pw.println("<thead class='ui-widget-header'>");
        pw.println("<tr>");
        pw.println("<th>Appender</th>");
        pw.println("<th>" + cfgColTitle + "</th>");
        pw.println("</tr>");
        pw.println("</thead>");
        pw.println("<tbody class='ui-widget-content'>");

        for(Appender<ILoggingEvent> appender : ctx.appenders.values()){
            pw.println("<tr>");
            pw.println("<td>" + getName(appender) + "</td>");
            pw.println("<td>" + formatPid(consoleAppRoot, appender,ctx)
                    + "</td>");
            pw.println("</tr>");
        }

        pw.println("</tbody>");
        pw.println("</table>");
        pw.println("</div>");
    }

    private String getName(Appender<ILoggingEvent> appender) {
        if(appender instanceof FileAppender){
            return "File : " + ((FileAppender) appender).getFile();
        }
        return String.format("%s (%s)",appender.getName(),appender.getClass().getName());
    }


    private String formatPid(final String consoleAppRoot,
                             final Appender<ILoggingEvent> appender, final LoggerStateContext ctx) {
        if(appender instanceof SlingRollingFileAppender){
            final LogWriter lw = ((SlingRollingFileAppender) appender).getLogWriter();
            if (lw.isImplicit()) {
                return "[implicit]";
            }

            final String pid = lw.getConfigurationPID();
            return createUrl(consoleAppRoot,"configMgr",pid);
        } else if (ctx.isDynamicAppender(appender)){
            final AppenderInfo ai = ctx.dynamicAppenders.get(appender);

            final String pid = ai.serviceReference.getProperty(Constants.SERVICE_ID).toString();
            return createUrl(consoleAppRoot,"services",pid);
        } else {
            return "[others]";
        }
    }

    private static String createUrl(String consoleAppRoot, String subContext, String pid){
        // no recent web console, so just render the pid as the link
        if (consoleAppRoot == null) {
            return "<a href=\""+subContext+"/" + pid + "\">" + pid + "</a>";
        }

        // recent web console has app root and hence we can use an image
        return "<a href=\""+subContext+"/" + pid + "\"><img src=\"" + consoleAppRoot
                + "/res/imgs/component_configure.png\" border=\"0\" /></a>";
    }

    private LogConfigManager getLogConfigManager(){
        return logbackManager.getLogConfigManager();
    }

    private LoggerStateContext determineLoggerState(List<Logger> loggers){
        LoggerStateContext ctx = new LoggerStateContext();
        for(Logger logger : loggers){
           if(logger.iteratorForAppenders().hasNext() || logger.getLevel() != null){
               ctx.loggerInfos.add(new LoggerInfo(logger));
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

    private class LoggerStateContext {
        final List<LoggerInfo> loggerInfos = new ArrayList<LoggerInfo>();
        final Map<String,Appender<ILoggingEvent>> appenders = new HashMap<String, Appender<ILoggingEvent>>();
        final Map<Appender<ILoggingEvent>, AppenderInfo> dynamicAppenders =
                new HashMap<Appender<ILoggingEvent>, AppenderInfo>();

        private LoggerStateContext() {
            for(AppenderInfo ai : logbackManager.getAppenderTracker().getAppenderInfos()){
                dynamicAppenders.put(ai.appender, ai);
            }
        }

        int getNumofSlingLogConfig(){
            return getLogConfigManager().getConfigByPid().size();
        }

        int getNumofSlingLogWriters(){
            return getLogConfigManager().getWriterByPid().size();
        }

        int getNumOfDynamicAppenders(){
            return logbackManager.getAppenderTracker().getAppenderInfos().size();
        }

        int getNumOfLogbackAppenders(){
            return appenders.size()
                    - getNumofSlingLogWriters()
                    - getNumOfDynamicAppenders();
        }

        boolean isDynamicAppender(Appender<ILoggingEvent> a){
            return dynamicAppenders.containsKey(a);
        }
    }

    private static class LoggerInfo {
        final Logger logger;

        private LoggerInfo(Logger logger) {
            this.logger = logger;
        }
    }
}
