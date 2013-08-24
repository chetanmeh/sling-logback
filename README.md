# Sling Logback Extension

Logback based logger for Sling ([SLING-2024](https://issues.apache.org/jira/browse/SLING-2024))

## Features

* Compatible with existing Sling Commons Log
* LogBack configuration can be provided via Logback config xml
* ConfigurationAdmin integration - Logback Config can be enhanced via config obtained from
  OSGi configuration admin
* Supports Appenders via Whiteboard pattern
* Support providing Logback config as fragments through OSGi Service Registry
* WebConsole Plugin and Configuration Printer support

### Appenders and Whiteboard pattern

The whiteboard suppoert simplifies the task of registering appenders with loggers. An appender
can be  registered by exporting it as a service. The whiteboard implementation detects all
`ch.qos.logback.core.Appender` with the right service properties.

```java
    Dictionary<String,Object> props = new Hashtable<String, Object>();

    String[] loggers = {
            "foo.bar:DEBUG",
            "foo.bar.zoo:INFO",
    };

    props.put("loggers",loggers);
    sr = bundleContext.registerService(Appender.class.getName(),this,props);
```

Service property `loggers` is a multi value property having following format

    <Logger Name>:<Level>:<additivity>

* Logger Name (required) - Name of the logger to which the appender has to be attached
* Level (Optional, default INFO) - Logging level e.g. INFO, WARN etc. See [Logback Manual][1]
* additivity (Optional, default false) - See Additivity in [Logback Manual][2]

### Logback Config Fragment Support

Logback supports including parts of a configuration file from another file (See [File Inclusion][4]). This module
extends that support by allowing other bundles to provide config fragments. There are two ways to achieve that

#### Exposing fragment as String objects

If you have the config as string then you can register that String instance as a service with property `logbackConfig`
set to true. Sling Logback Extension would monitor such objects and pass them to logback

```java
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
```

See [ConfigExample][5] for an example. If the config needs to be updated just re-register the service and
change would be picked up

#### Exposing fragment as ConfigProvider instance

Another way to provide config fragment is by providing an implementation of `org.apache.sling.extensions.logback.ConfigProvider`

```java
    @Component
    @Service
    public class ConfigProviderExample implements ConfigProvider {
        public InputSource getConfigSource() {
            return new InputSource(getClass().getClassLoader().getResourceAsStream("foo-config.xml"));
        }
    }
```

See [ConfigProviderExample][6] for an example.

If the config changes then sending an event to `org/apache/sling/commons/log/RESET` would reset the listener

```java
  eventAdmin.sendEvent(new Event("org/apache/sling/commons/log/RESET",new Properties()));
```

### WebConsole Plugin enhancements

The web Console Plugin supports following features

* Displays list of loggers which have level or appender configured
* List of File appenders with location of current active files
* Content of LogBack config file
* Content of various Logback config fragment
* Logback Status logs

![Web Console Plugin](http://chetanmeh.github.com/images/sling-log-support.png)

## TODO

* ~~Support for providing LogBack config as fragments - It should be possible to add [Logback Fragments][4]
  without modifying original file. Instead the fragment config can be provided via OSGi service registry~~
* ~~WebConsole plugin to expose internal state~~
* ~~WebConsole Status printer to provide access to the various log files~~
* ~~Integration testcase~~
* ~~Expose LogBack status through WebConsole Plugin~~
* ~~Support integration with EventAdmin~~
* JUL Integration
* Editing Logback config via Web Console
* Integration with [Felix Inventory Support][7]

## References

 * [Logback](http://logback.qos.ch/)
 * [Pax Logging](https://github.com/ops4j/org.ops4j.pax.logging/tree/master/pax-logging-logback)
 * [Sling Commons Log](http://sling.apache.org/site/logging.html)
 * [Felix Meschberger Prototype](https://svn.apache.org/repos/asf/sling/whiteboard/fmeschbe/logback/)
 * Discussions on Sling mailing list
     * http://markmail.org/thread/66hrpdixaahvtyy5
     * http://markmail.org/thread/etcayimn6ili3edr


[1]: http://logback.qos.ch/manual/configuration.html#loggerElement
[2]: http://logback.qos.ch/manual/architecture.html#AppendersAndLayouts
[3]: https://github.com/chetanmeh/sling-logback/blob/master/example/src/main/java/org/apache/sling/examples/logback/FilteringAppender.java
[4]: http://logback.qos.ch/manual/configuration.html#fileInclusion
[5]: https://github.com/chetanmeh/sling-logback/blob/master/example/src/main/java/org/apache/sling/examples/logback/ConfigExample.java
[6]: https://github.com/chetanmeh/sling-logback/blob/master/example/src/main/java/org/apache/sling/examples/logback/ConfigProviderExample.java
[7]: http://felix.apache.org/documentation/subprojects/apache-felix-inventory.html
