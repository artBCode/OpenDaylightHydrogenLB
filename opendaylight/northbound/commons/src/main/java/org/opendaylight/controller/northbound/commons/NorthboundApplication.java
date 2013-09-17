/**
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.northbound.commons;

import java.util.Dictionary;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.ws.rs.core.Application;
import javax.ws.rs.ext.ContextResolver;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlRootElement;

import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;
import org.opendaylight.controller.northbound.bundlescanner.IBundleScanService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleReference;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceException;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instance of javax.ws.rs.core.Application used to return the classes
 * that will be instantiated for JAXRS processing. This hooks onto the
 * bundle scanner service to provide JAXB classes to JAX-RS for prorcessing.
 */
@SuppressWarnings("unchecked")
public class NorthboundApplication extends Application {
    public static final String JAXRS_RESOURCES_MANIFEST_NAME = "Jaxrs-Resources";
    private static final Logger LOGGER = LoggerFactory.getLogger(NorthboundApplication.class);

    ////////////////////////////////////////////////////////////////
    //  Application overrides
    ////////////////////////////////////////////////////////////////

    @Override
    public Set<Object> getSingletons() {
        Set<Object> singletons = new HashSet<Object>();
        singletons.add(new ContextResolver<JAXBContext>() {
            @Override
            public JAXBContext getContext(Class<?> type) {
                return newJAXBContext();
            }

        } );
        singletons.add(new JacksonJaxbJsonProvider());
        return singletons;
    }

    @Override
    public Set<Class<?>> getClasses() {
        Set<Class<?>> result = new HashSet<Class<?>>();
        result.addAll(findJAXRSResourceClasses());
        return result;
    }

    private BundleContext getBundleContext() {
        ClassLoader tlcl = Thread.currentThread().getContextClassLoader();
        Bundle bundle = null;

        if (tlcl instanceof BundleReference) {
            bundle = ((BundleReference) tlcl).getBundle();
        } else {
            LOGGER.warn("Unable to determine the bundle context based on " +
                        "thread context classloader.");
            bundle = FrameworkUtil.getBundle(this.getClass());
        }
        return (bundle == null ? null : bundle.getBundleContext());
    }

    private static final IBundleScanService lookupBundleScanner(BundleContext ctx) {
        ServiceReference svcRef = ctx.getServiceReference(IBundleScanService.class);
        if (svcRef == null) {
            throw new ServiceException("Unable to lookup IBundleScanService");
        }
        return IBundleScanService.class.cast(ctx.getService(svcRef));
    }

    private final JAXBContext newJAXBContext() {
        BundleContext ctx = getBundleContext();
        IBundleScanService svc = lookupBundleScanner(ctx);
        try {
            List<Class<?>> cls = svc.getAnnotatedClasses(ctx,
                    new String[] { XmlRootElement.class.getPackage().getName() },
                    true);
            return JAXBContext.newInstance(cls.toArray(new Class[cls.size()]));
        } catch (JAXBException je) {
            LOGGER.error("Error creating JAXBContext", je);
            return null;
        }
    }

    private final Set<Class<?>> findJAXRSResourceClasses() {
        BundleContext ctx = getBundleContext();
        String bundleName = ctx.getBundle().getSymbolicName();
        Set<Class<?>> result = new HashSet<Class<?>>();
        ServiceException recordException = null;
        try {
            IBundleScanService svc = lookupBundleScanner(ctx);
            result.addAll(svc.getAnnotatedClasses(ctx,
                    new String[] { javax.ws.rs.Path.class.getName() }, false));
        } catch (ServiceException se) {
            recordException = se;
            LOGGER.debug("Error finding JAXRS resource annotated classes in " +
                    "bundle: {} error: {}.", bundleName, se.getMessage());
            // the bundle scan service cannot be lookedup. Lets attempt to
            // lookup the resources from the bundle manifest header
            Dictionary<String,String> headers = ctx.getBundle().getHeaders();
            String header = headers.get(JAXRS_RESOURCES_MANIFEST_NAME);
            if (header != null) {
                for (String s : header.split(",")) {
                    s = s.trim();
                    if (s.length() > 0) {
                        try {
                            result.add(ctx.getBundle().loadClass(s));
                        } catch (ClassNotFoundException cnfe) {
                            LOGGER.error("Cannot load class: {} in bundle: {} " +
                                    "defined as MANIFEST JAX-RS resource", s, bundleName, cnfe);
                        }
                    }
                }
            }

        }

        if (result.size() == 0) {
            if (recordException != null) {
                throw recordException;
            } else {
                throw new ServiceException("No resource classes found in bundle:" +
                        ctx.getBundle().getSymbolicName());
            }
        }
        return result;
    }

}