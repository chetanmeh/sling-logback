# Sling Logback Extension

Logback based logger for Sling ([SLING-2024](https://issues.apache.org/jira/browse/SLING-2024))

## Features

* Compatible with existing Sling Commons Log
* LogBack configuration can be provided via Logback config xml
* ConfigurationAdmin integration - Logback Config can be enhanced via config obtained from
  OSGi configuration admin
* Supports Appenders registered as OSGi Services
* Supports Filters and TurboFilters registered as OSGi Services
* Support providing Logback config as fragments through OSGi Service Registry
* FEature rich WebConsole Plugin and Configuration Printer support

### Logback Filter Support

Its possible to register [Logback Filters][10] as OSGi services.

#### Registering TurboFilters

TurboFilter operate globally and invoked for every Logback call. The bundle just need to register a
service  against `ch.qos.logback.classic.turbo.TurboFilter` class

```java
    import import ch.qos.logback.classic.turbo.MatchingFilter;

    SimpleTurboFilter stf = new SimpleTurboFilter();
    ServiceRegistration sr  = bundleContext.registerService(TurboFilter.class.getName(), stf, null);

    private static class SimpleTurboFilter extends MatchingFilter {
        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level, String format,
         Object[] params, Throwable t) {
            if(logger.getName().equals("turbofilter.foo.bar")){
                    return FilterReply.DENY;
            }
            return FilterReply.NEUTRAL;
        }
    }
```

As these filters are invoked for every call they must not take much time to execute

#### Registering Filters

Logback Filters are attached to appenders and are used to determine if any LoggingEvent needs to
be passed to the appender. When registering a filter the bundle needs to configure a service property
`appenders` which refers to list of appender names to which the Filter must be attached

```java
    import ch.qos.logback.core.filter.Filter;

    SimpleFilter stf = new SimpleFilter();
    Dictionary<String, Object> props = new Hashtable<String, Object>();
    props.put("appenders", "TestAppender");
    ServiceRegistration sr  = bundleContext.registerService(Filter.class.getName(), stf, props);

    private static class SimpleFilter extends Filter<ILoggingEvent> {

        @Override
        public FilterReply decide(ILoggingEvent event) {
            if(event.getLoggerName().equals("filter.foo.bar")){
                return FilterReply.DENY;
            }
            return FilterReply.NEUTRAL;
        }
    }

```

### Appenders and Whiteboard pattern

The whiteboard support simplifies the task of registering appenders with loggers. An appender
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

### External Config File

Logback can be configured with an external file. The file name can be specified through

1. OSGi config - Look for config with name `Apache Sling Logging Configuration` and specify the path for
   config file property
2. OSGi Framework Properties - Logback supports also looks for file name with property name
   `org.apache.sling.commons.log.configurationFile`

If you are providing an external config file then to support OSGi integration you would need to add following
action entry

```xml
    <newRule pattern="*/configuration/osgi"
             actionClass="org.apache.sling.extensions.logback.OsgiAction"/>
    <osgi/>
```

### Java Util Logging (JUL) Integration

The bundle also support [SLF4JBridgeHandler][9]. To enable JUL integration following two steps
needs to be done

1. Set framework property `org.apache.sling.commons.log.julenabled` to true
2. Set the [LevelChangePropagator][8] in LogbackConfig

```xml
        <configuration>
            <contextListener class="ch.qos.logback.classic.jul.LevelChangePropagator"/>
            ...
        </configuration>
```

See [SLING-2193](https://issues.apache.org/jira/browse/SLING-2193) for details.

### Configuring OSGi based appenders in Logback Config

So far Sling used to configure the appenders based on OSGi config. That mode only provide a very limited
set to configuration options. To make use of other Logback features you can override the OSGi config
from withing the Logback config file. OSGi config based appenders are named based on the file name

For example for following OSGi config

```
org.apache.sling.commons.log.file="logs/error.log"
org.apache.sling.commons.log.level="info"
org.apache.sling.commons.log.file.size="'.'yyyy-MM-dd"
org.apache.sling.commons.log.file.number=I"7"
org.apache.sling.commons.log.pattern="{0,date,dd.MM.yyyy HH:mm:ss.SSS} *{4}* [{2}] {3} {5}"
```

The Logback appender would be named as `logs/error.log`. To extend/override the config in Logback config
create an appender with name `logs/error.log`

```
   <appender name="/logs/error.log" class="ch.qos.logback.core.FileAppender">
    <file>${sling.home}/logs/error.log</file>
    <encoder>
      <pattern>%d %-5level %X{sling.userId:-NA} [%thread] %logger{30} %marker- %msg %n</pattern>
      <immediateFlush>true</immediateFlush>
    </encoder>
  </appender>

```

In this case then Log module would create appender based on Logback config instead of OSGi config. This can
be used to move the application from OSGi based config to Logback based config easily

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
* ~~JUL Integration~~
* ~~Logback Filters as OSGi Services~~
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
[8]: http://logback.qos.ch/manual/configuration.html#LevelChangePropagator
[9]: http://www.slf4j.org/api/org/slf4j/bridge/SLF4JBridgeHandler.html
[10]: http://logback.qos.ch/manual/filters.html
