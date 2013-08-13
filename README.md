# Sling Logback Extension

Logback based logger for Sling

## Features

* Compatible with existing Sling Commons Log
* LogBack configuration can be provided via Logback config xml
* ConfigurationAdmin integration - Logback Config can be enhanced via config obtained from
  OSGi configuration admin
* Supports Appenders via Whiteboard pattern

### Appenders and Whiteboard pattern

The whiteboard suppoert simplifies the task of registering appenders with loggers. An appender
can be  registered by exporting it as a service. The whiteboard implementation detects all
`ch.qos.logback.core.Appender` with the right service properties.

    :::java
    Dictionary<String,Object> props = new Hashtable<String, Object>();

    String[] loggers = {
            "foo.bar:DEBUG",
            "foo.bar.zoo:INFO",
    };

    props.put("loggers",loggers);
    sr = bundleContext.registerService(Appender.class.getName(),this,props);

Service property `loggers` is a multi value property having following format

  <Logger Name>:<Level>:<additivity>

* Logger Name (required) - Name of the logger to which the appender has to be attached
* Level (Optional, default INFO) - Logging level e.g. INFO, WARN etc. See [Logback Manual][1]
* additivity (Optional, default false) - See Additivity in [Logback Manual][2]

## TODO

* Support for providing LogBack config as fragments
* WebConsole plugin to expose internal state
* WebConsole Status printer to provide access to the various log files

## References

 * [Logback](http://logback.qos.ch/)
 * [Pax Logging](https://github.com/ops4j/org.ops4j.pax.logging/tree/master/pax-logging-logback)
 * [Sling Commons Log](http://sling.apache.org/site/logging.html)
 * [Felix Meschberger Prototype](https://svn.apache.org/repos/asf/sling/whiteboard/fmeschbe/logback/)
 * Discussions on Sling mailing list
 ** http://markmail.org/thread/66hrpdixaahvtyy5
 ** http://markmail.org/thread/etcayimn6ili3edr


[1]: http://logback.qos.ch/manual/configuration.html#loggerElement
[2]: http://logback.qos.ch/manual/architecture.html#AppendersAndLayouts