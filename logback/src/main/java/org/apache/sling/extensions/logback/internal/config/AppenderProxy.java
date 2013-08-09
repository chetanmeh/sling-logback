/*************************************************************************
 *
 * ADOBE CONFIDENTIAL
 * ___________________
 *
 *  Copyright 2013 Adobe Systems Incorporated
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Adobe Systems Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Adobe Systems Incorporated and its
 * suppliers and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Adobe Systems Incorporated.
 **************************************************************************/
package org.apache.sling.extensions.logback.internal.config;

import java.text.MessageFormat;
import java.util.List;
import java.util.regex.Pattern;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.LogbackException;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterAttachableImpl;
import ch.qos.logback.core.spi.FilterReply;

public class AppenderProxy extends Filter<ILoggingEvent> implements Appender<ILoggingEvent> {

    private final AppenderKey key;

    private final FilterAttachableImpl<ILoggingEvent> filters;

    private Appender<ILoggingEvent> delegatee;

    AppenderProxy(final AppenderKey key) {
        this.key = key;
        this.filters = new FilterAttachableImpl<ILoggingEvent>();

        this.configure();
    }

    public AppenderKey getKey() {
        return key;
    }

    public LogWriter getWriter() {
        return getKey().getWriter();
    }

    public String getPattern() {
        return getKey().getPattern();
    }

    void configure() {
        boolean wasStarted = this.isStarted();
        if (wasStarted) {
            this.stop();
        }

        Appender<ILoggingEvent> appender = this.getWriter().createAppender(this.getContext(), this.getEncoder());
        appender.setContext(this.getContext());
        appender.addFilter(this);
        appender.start();

        this.delegatee = appender;
    }

    public void start() {
        if (delegatee != null) {
            delegatee.start();
        }

        super.start();
    }

    public void stop() {
        super.stop();

        if (delegatee != null) {
            delegatee.stop();
        }
    }

    public boolean isStarted() {
        return super.isStarted() && delegatee != null && delegatee.isStarted();
    }

    public void doAppend(ILoggingEvent event) throws LogbackException {
        if (this.delegatee != null) {
            this.delegatee.doAppend(event);
        }
    }

    // Filter management

    public void addFilter(Filter<ILoggingEvent> newFilter) {
        this.filters.addFilter(newFilter);
    }

    public List<Filter<ILoggingEvent>> getCopyOfAttachedFiltersList() {
        return this.filters.getCopyOfAttachedFiltersList();
    }

    public FilterReply getFilterChainDecision(ILoggingEvent event) {
        return this.filters.getFilterChainDecision(event);
    }

    public void clearAllFilters() {
        this.filters.clearAllFilters();
    }

    // Filter

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return getFilterChainDecision(event);
    }

    // ContextAwareBase

    public void setContext(Context context) {
        super.setContext(context);
        if (this.delegatee != null) {
            this.delegatee.setContext(context);
        }
    }

    // generate the encoder from the pattern

    /**
     * Returns a preset encoder without context and not started yet
     */
    private Encoder<ILoggingEvent> getEncoder() {
        Pattern date = Pattern.compile("\\{0,date,(.+)\\}");
        String logBackPattern = date.matcher(getPattern()).replaceAll("d{$1}");

        logBackPattern = MessageFormat.format(logBackPattern, "zero", "%marker", "%thread", "%logger", "%level",
            "%message") + "%n";

        PatternLayoutEncoder pl = new PatternLayoutEncoder();
        pl.setPattern(logBackPattern);
        pl.setContext(this.getContext());
        pl.start();

        return pl;
    }
}
