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

import java.util.Dictionary;
import java.util.Hashtable;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

@Component(immediate = true)
public class FilteringAppender extends AppenderBase<ILoggingEvent>{
    private String filterProp = "foo";
    private Logger log = LoggerFactory.getLogger(getClass());
    private ServiceRegistration sr;

    public FilteringAppender() {
        setName("FooAppender");
    }

    @Override
    protected void append(ILoggingEvent e) {
        Marker m = e.getMarker();
        boolean process = false;
        if(m != null && filterProp.equals(m.getName())){
            process = true;
        }else if(e.getMDCPropertyMap().containsKey(filterProp)){
            process = true;
        }

        if(process){
            e.prepareForDeferredProcessing();
            log.warn("==== {}",e.getFormattedMessage());
        }
    }

    @Activate
    private void activate(BundleContext bundleContext){
        Dictionary<String,Object> props = new Hashtable<String, Object>();

        String[] loggers = {
                "foo.bar:DEBUG",
                "foo.bar.zoo:INFO",
        };

        props.put("loggers",loggers);
        sr = bundleContext.registerService(Appender.class.getName(),this,props);
    }

    @Deactivate
    private void deactivate(){
        if(sr != null){
            sr.unregister();
        }
    }
}
