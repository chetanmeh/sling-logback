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
package org.apache.sling.extensions.logback.internal.config.osgi;

import java.util.Dictionary;

import org.osgi.service.cm.ManagedServiceFactory;

class LogWriterManagedServiceFactory extends LogConfigurator implements
        ManagedServiceFactory {

    public String getName() {
        return "LogWriter configurator";
    }

    @SuppressWarnings("unchecked")
    public void updated(String pid, Dictionary configuration)
            throws org.osgi.service.cm.ConfigurationException {
        try {
            getLogConfigManager().updateLogWriter(pid, configuration);
        } catch (ConfigurationException ce) {
            throw new org.osgi.service.cm.ConfigurationException(
                ce.getProperty(), ce.getReason(), ce);
        }

    }

    public void deleted(String pid) {
        try {
            getLogConfigManager().updateLogWriter(pid, null);
        } catch (ConfigurationException ce) {
            // not expected
            getLogConfigManager().internalFailure(
                    "Unexpected Configuration Problem", ce);
        }
    }
}