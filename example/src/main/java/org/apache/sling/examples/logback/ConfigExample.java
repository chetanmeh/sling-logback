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
