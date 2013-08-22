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

package org.apache.sling.extensions.logback.integration;

import java.util.Properties;

import javax.inject.Inject;

import org.apache.sling.extensions.logback.ConfigProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.BundleContext;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class ITConfigFragments extends LogTestBase {

    @Inject
    private BundleContext bundleContext;

    static {
        // uncomment to enable debugging of this test class
//                paxRunnerVmOption = DEBUG_VM_OPTION;

    }


    @Test
    public void testConfigFragment() throws Exception {
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
                "  <logger name=\"foo.bar.include\" level=\"DEBUG\">\n" +
                "       <appender-ref ref=\"FOOFILE\" />\n" +
                "  </logger>\n" +
                "\n" +
                "</included>";

        bundleContext.registerService(String.class.getName(),config,props);

        delay();

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("foo.bar.include");
        assertTrue(logger.isDebugEnabled());
        assertNotNull("Appender FOOFILE must be attached",logger.getAppender("FOOFILE"));
    }


    @Test
    public void testConfigProvider() throws Exception {
        bundleContext.registerService(ConfigProvider.class.getName(),new ConfigProviderExample(),null);

        delay();

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("foo2.bar.include");
        assertTrue(logger.isDebugEnabled());
        assertNotNull("Appender FOO2FILE must be attached",logger.getAppender("FOO2FILE"));
    }

    private static class ConfigProviderExample implements ConfigProvider {
        public InputSource getConfigSource() {
            return new InputSource(getClass().getClassLoader().getResourceAsStream("foo-config.xml"));
        }
    }
}
