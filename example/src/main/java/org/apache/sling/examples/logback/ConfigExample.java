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

package org.apache.sling.examples.logback;

import java.util.Properties;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

@Component(immediate = true)
public class ConfigExample {
    private ServiceRegistration registration;

    @Activate
    private void activate(BundleContext context){
        Properties props = new Properties();
        props.setProperty("logbackConfig","true");

        String config = "<included>\n" +
                "  <appender name=\"FOOFILE\" class=\"ch.qos.logback.core.FileAppender\">\n" +
                "    <file>${sling.home}/logs/foo.log</file>\n" +
                "    <encoder>\n" +
                "      <pattern>%d %-5level %logger{35} - %msg %n</pattern>\n" +
                "    </encoder>\n" +
                "  </appender>\n" +
                "\n" +
                "  <logger name=\"foo.bar.include\" level=\"INFO\">\n" +
                "       <appender-ref ref=\"FOOFILE\" />\n" +
                "  </logger>\n" +
                "\n" +
                "</included>";

        registration = context.registerService(String.class.getName(),config,props);
    }

    @Deactivate
    public void deactivate(){
        if(registration != null){
            registration.unregister();
        }
    }
}
