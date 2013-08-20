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
import java.io.StringWriter;
import java.net.URL;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.helpers.Transform;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.CachingDateFormatter;
import org.apache.sling.extensions.logback.internal.LogbackManager.LoggerStateContext;
import org.apache.sling.extensions.logback.internal.util.SlingRollingFileAppender;
import org.osgi.framework.Constants;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

import static org.apache.sling.extensions.logback.internal.AppenderTracker.AppenderInfo;
import static org.apache.sling.extensions.logback.internal.ConfigSourceTracker.ConfigSourceInfo;


/**
 * The <code>SlingLogPanel</code> is a Felix Web Console plugin to display the
 * current active log bundle configuration.
 * <p>
 * In future revisions of this plugin, the configuration may probably even
 * be modified through this panel.
 */
public class SlingLogPanel extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final CachingDateFormatter SDF = new CachingDateFormatter("yyyy-MM-dd HH:mm:ss");

    private final LogbackManager logbackManager;

    private final String labelRes;
    private final int labelResLen;

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(SlingLogPanel.class);

    public SlingLogPanel(final LogbackManager logbackManager, String label) {
        this.logbackManager = logbackManager;
        this.labelRes = '/' + label + '/';
        this.labelResLen = labelRes.length() - 1;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        final PrintWriter pw = resp.getWriter();

        final String consoleAppRoot = (String) req.getAttribute("felix.webconsole.appRoot");
        final String pluginRoot = (String) req.getAttribute("felix.webconsole.pluginRoot");

        final LoggerStateContext ctx = logbackManager.determineLoggerState();
        appendLoggerStatus(pw, ctx);
        appendLoggerData(pw, ctx);
        addAppenderData(pw, consoleAppRoot, ctx);
        appendLogbackFragments(pw, consoleAppRoot,pluginRoot);
        appendLogbackStatus(pw, ctx);
    }

    private void appendLoggerStatus(PrintWriter pw, LoggerStateContext ctx) {
        pw.printf(
            "<p class='statline'>Log Service Stats: %d categories, %d appenders(s), %d Dynamic appenders(s)</p>%n",
                ctx.getNumberOfLoggers(),
                ctx.getNumOfAppenders(),
                ctx.getNumOfDynamicAppenders()
        );
    }

    private void appendLoggerData(PrintWriter pw, LoggerStateContext ctx) {
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

        for(Logger logger : ctx.loggerInfos){
            pw.println("<tr>");
            pw.println("<td>" + logger.getLevel() + "</td>");
            pw.println("<td>" + logger.getName() + "</td>");

            pw.println("<td>");
            pw.println("<ul>");
            Iterator<Appender<ILoggingEvent>> itr = logger.iteratorForAppenders();
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
    }

    private void addAppenderData(PrintWriter pw, String consoleAppRoot,LoggerStateContext ctx) {
        pw.println("<div class='table'>");

        pw.println("<div class='ui-widget-header ui-corner-top buttonGroup'>Appender</div>");

        pw.println("<table class='nicetable ui-widget'>");

        pw.println("<thead class='ui-widget-header'>");
        pw.println("<tr>");
        pw.println("<th>Appender</th>");
        pw.println("<th>" + getConfigColTitle(consoleAppRoot) + "</th>");
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

    private void appendLogbackStatus(PrintWriter pw, LoggerStateContext ctx) {
        pw.println("<div class='table'>");

        pw.println("<div class='ui-widget-header ui-corner-top buttonGroup'>Logback Status</div>");

        pw.println("<table class='nicetable ui-widget'>");

        pw.println("<thead class='ui-widget-header'>");
        pw.println("<tr>");
        pw.println("<th>Date</th>");
        pw.println("<th>Level</th>");
        pw.println("<th>Origin</th>");
        pw.println("<th>Message</th>");
        pw.println("</tr>");
        pw.println("</thead>");
        pw.println("<tbody class='ui-widget-content'>");

        List<Status> statusList = ctx.loggerContext.getStatusManager().getCopyOfStatusList();
        for (Status s : statusList) {
            pw.println("<tr>");
            pw.println("<td class=\"date\">" +  SDF.format(s.getDate()) + "</td>");
            pw.println("<td class=\"level\">" + statusLevelAsString(s) + "</td>");
            pw.println("<td>" + abbreviatedOrigin(s) + "</td>");
            pw.println("<td>" + s.getMessage() + "</td>");
            pw.println("</tr>");

            //noinspection ThrowableResultOfMethodCallIgnored
            if (s.getThrowable() != null) {
                printThrowable(pw, s.getThrowable());
            }
        }

        pw.println("</tbody>");
        pw.println("</table>");
        pw.println("</div>");
    }

    private void appendLogbackFragments(PrintWriter pw, String consoleAppRoot, String pluginRoot) {
        final Collection<ConfigSourceInfo> configSources =
                logbackManager.getConfigSourceTracker().getSources();

        if(configSources.isEmpty()){
            return;
        }

        pw.printf("<script type=\"text/javascript\" src=\"%s/res/ui/prettify.js\"></script>", pluginRoot);
        pw.println("<script>$(document).ready(prettyPrint);</script>");
        pw.println("<div class='table'>");
        pw.println("<div class='ui-widget-header ui-corner-top buttonGroup'>Logback Config Fragments</div>");
        pw.println("<table class='nicetable ui-widget'>");
        pw.println("<tbody class='ui-widget-content'>");

        for(ConfigSourceInfo ci : configSources){
            final String pid = ci.getReference().getProperty(Constants.SERVICE_ID).toString();
            String url = createUrl(consoleAppRoot,"services",pid);
            pw.println("<tr>");
            pw.println("<td>" + url + "</td>");
            pw.println("</tr>");

            pw.println("<tr>");
            pw.println("<td>");
            //prettify.js adds a border. We eed to remove that
            pw.println("<pre class=\"prettyprint lang-xml\" style=\"border: 0px\">");
            pw.print(escapeXml(prettyPrint(ci.getConfigProvider().getConfigSource())));
            pw.println("</pre>");

            pw.println("</td>");
            pw.println("</tr>");

        }

        pw.println("</tbody>");
        pw.println("</table>");
        pw.println("</div>");
    }

    /**
     * Called internally by {@link AbstractWebConsolePlugin} to load resources.
     *
     * This particular implementation depends on the label. As example, if the
     * plugin is accessed as <code>/system/console/abc</code>, and the plugin
     * resources are accessed like <code>/system/console/abc/res/logo.gif</code>,
     * the code here will try load resource <code>/res/logo.gif</code> from the
     * bundle, providing the plugin.
     *
     *
     * @param path the path to read.
     * @return the URL of the resource or <code>null</code> if not found.
     */
    @SuppressWarnings("UnusedDeclaration")
    protected URL getResource( String path )
    {
        return ( path != null && path.startsWith( labelRes ) ) ? //
                getClass().getResource( path.substring( labelResLen ) )
                : null;
    }


    private static String getName(Appender<ILoggingEvent> appender) {
        if(appender instanceof FileAppender){
            return "File : " + ((FileAppender) appender).getFile();
        }
        return String.format("%s (%s)",appender.getName(),appender.getClass().getName());
    }


    private static String formatPid(final String consoleAppRoot,
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

    private static String getConfigColTitle(String consoleAppRoot){
        return (consoleAppRoot == null) ? "PID" : "Configuration";
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

    //~------------------------------------------------Status Manager
    //Based on ch.qos.logback.core.status.ViewStatusMessagesServletBase

    private static String statusLevelAsString(Status s) {
        switch (s.getEffectiveLevel()) {
            case Status.INFO:
                return "INFO";
            case Status.WARN:
                return "<span class=\"warn\">WARN</span>";
            case Status.ERROR:
                return "<span class=\"error\">ERROR</span>";
        }
        return null;
    }

    private static String abbreviatedOrigin(Status s) {
        Object o = s.getOrigin();
        if (o == null) {
            return null;
        }
        String fqClassName = o.getClass().getName();
        int lastIndex = fqClassName.lastIndexOf(CoreConstants.DOT);
        if (lastIndex != -1) {
            return fqClassName.substring(lastIndex + 1, fqClassName.length());
        } else {
            return fqClassName;
        }
    }

    private static void printThrowable(PrintWriter pw, Throwable t) {
        pw.println("  <tr>");
        pw.println("    <td colspan=\"4\" class=\"exception\"><pre>");
        StringWriter sw = new StringWriter();
        PrintWriter expPw = new PrintWriter(sw);
        t.printStackTrace(expPw);
        pw.println(Transform.escapeTags(sw.getBuffer()));
        pw.println("    </pre></td>");
        pw.println("  </tr>");
    }

    //~------------------------ ConfigFragment

    private static String prettyPrint(InputSource is) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            //initialize StreamResult with File object to save to file
            StreamResult result = new StreamResult(new StringWriter());
            Source source = new SAXSource(is);
            transformer.transform(source, result);
            return result.getWriter().toString();
        } catch (Exception e) {
            //Catch generic error as panel should still work if xml apis are not
            //resolved
            log.warn("Error occurred while transforming xml", e);
        }

        return "Source not found";
    }

    private static String escapeXml(String xml){
        return xml.replaceAll("<","&lt;").replaceAll(">","&gt;");
    }
}
