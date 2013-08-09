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

/**
 * The <code>AppenderKey</code> encapsulates a {@link LogWriter} file name and a
 * {@link LogConfig} logger pattern.
 */
public final class AppenderKey {

    private final LogWriter writer;

    private final String pattern;

    public AppenderKey(final LogWriter writer, final String pattern) {
        this.writer = writer;
        this.pattern = pattern;
    }

    public LogWriter getWriter() {
        return writer;
    }

    public String getPattern() {
        return pattern;
    }

    @Override
    public int hashCode() {
        return this.writer.hashCode() * 33 + this.pattern.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (obj instanceof AppenderKey) {
            final AppenderKey other = (AppenderKey) obj;
            return this.writer == other.writer && this.pattern.equals(other.pattern);
        }

        return false;
    }
}
