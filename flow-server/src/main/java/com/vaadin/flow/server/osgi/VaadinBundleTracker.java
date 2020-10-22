/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.flow.server.osgi;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.Servlet;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.LoggerFactory;

/**
 * Bundle tracker to discover all classes in active bundles.
 * <p>
 * The tracker scans for all classes in active bundles which have
 * <b>Vaadin-OSGi-Extender</b> header and report them to the {@link OSGiAccess}
 * instance.
 *
 * @author Vaadin Ltd
 * @since 1.2
 */
public class VaadinBundleTracker extends BundleTracker<Bundle> {

    private final Bundle flowServerBundle;

    private Executor executor = Executors.newSingleThreadExecutor();

    private final AtomicReference<ServiceRegistration<Servlet>> servletRegistration = new AtomicReference<>();

    private static class PushResourceServlet extends HttpServlet {

        private final Bundle bundle;

        public PushResourceServlet(Bundle pushBundle) {
            bundle = pushBundle;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            String pathInfo = req.getPathInfo();
            if (pathInfo == null) {
                resp.setStatus(HttpURLConnection.HTTP_NOT_FOUND);
            }
            URL resource = bundle.getResource(
                    "/META-INF/resources/VAADIN/static/push" + pathInfo);
            if (resource == null) {
                resp.setStatus(HttpURLConnection.HTTP_NOT_FOUND);
            }
            try (InputStream stream = resource.openStream()) {
                IOUtils.copy(stream, resp.getOutputStream());
            }
        }
    }

    /**
     * Creates a new instance of a bundle tracker.
     *
     * @param context
     *            the {@code BundleContext} against which the tracking is done
     */
    public VaadinBundleTracker(BundleContext context) {
        super(context, Bundle.ACTIVE | Bundle.RESOLVED, null);
        flowServerBundle = context.getBundle();
    }

    @Override
    public Bundle addingBundle(Bundle bundle, BundleEvent event) {
        if ((bundle.getState() & Bundle.ACTIVE) != 0) {
            // Don't scan every individual bundle until flow-server is active
            if (flowServerBundle.equals(bundle)) {
                // First: scan for servlet context initializers in flow-server
                // bundle to reuse the same logic
                executor.execute(this::scanContextInitializers);
                // Now scan all active bundles for all classes instead of
                // scanning every inidividual activated bundle/
                executor.execute(this::scanActiveBundles);
            } else if (isPushModule(bundle)) {
                registerPushResources(bundle);
            } else if ((flowServerBundle.getState() & Bundle.ACTIVE) != 0) {
                // If flow-server bundle is already active then scan bundle for
                // classes
                executor.execute(() -> scanActivatedBundle(bundle));
            }
        } else if (event != null
                && ((event.getType() & BundleEvent.STOPPED) > 0)) {
            if (isPushModule(bundle)) {
                unregisterPushResource(bundle);
            } else if (isVaadinExtender(bundle)) {
                // Remove all bundle classes once the bundle becomes stopped
                OSGiAccess.getInstance()
                        .removeScannedClasses(bundle.getBundleId());
            }
        }
        return bundle;
    }

    private void registerPushResources(Bundle pushBundle) {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN,
                "/VAADIN/static/push/*");
        servletRegistration.compareAndSet(null,
                pushBundle.getBundleContext().registerService(Servlet.class,
                        new PushResourceServlet(pushBundle), properties));
    }

    private void unregisterPushResource(Bundle pushBundle) {
        ServiceRegistration<Servlet> registration = servletRegistration.get();
        if (registration != null && registration.getReference().getBundle()
                .getBundleId() == pushBundle.getBundleId()) {
            registration.unregister();
            servletRegistration.compareAndSet(registration, null);
        }
    }

    private boolean isPushModule(Bundle bundle) {
        return "com.vaadin.flow.push".equals(bundle.getSymbolicName());
    }

    @SuppressWarnings("unchecked")
    private void scanContextInitializers() {
        Map<Long, Collection<Class<?>>> map = new HashMap<>();
        scanClasses(flowServerBundle, map, this::handleFlowServerClassError);
        Collection<Class<?>> classes = map.get(flowServerBundle.getBundleId());

        Predicate<Class<?>> isInterface = Class::isInterface;

        Collection<Class<? extends ServletContainerInitializer>> initializers = classes
                .stream()
                .filter(ServletContainerInitializer.class::isAssignableFrom)
                .filter(isInterface.negate())
                .map(clazz -> (Class<? extends ServletContainerInitializer>) clazz)
                .collect(Collectors.toList());
        OSGiAccess.getInstance().setServletContainerInitializers(initializers);
    }

    private void scanActivatedBundle(Bundle bundle) {
        if (!isActive(bundle) || !isVaadinExtender(bundle)) {
            return;
        }
        if (OSGiAccess.getInstance().hasInitializers()) {
            Map<Long, Collection<Class<?>>> map = new HashMap<>();
            scanClasses(bundle, map, this::handleBundleClassError);
            OSGiAccess.getInstance().addScannedClasses(map);
        } else {
            executor.execute(() -> scanActivatedBundle(bundle));
        }
    }

    private void scanActiveBundles() {
        Map<Long, Collection<Class<?>>> map = new HashMap<>();
        Stream.of(flowServerBundle.getBundleContext().getBundles())
                .filter(this::isActive).filter(this::isVaadinExtender)
                .forEach(activeBundle -> scanClasses(activeBundle, map,
                        this::handleBundleClassError));
        OSGiAccess.getInstance().addScannedClasses(map);
    }

    private boolean isActive(Bundle bundle) {
        return (bundle.getState() & Bundle.ACTIVE) > 0;
    }

    private boolean isVaadinExtender(Bundle bundle) {
        return !flowServerBundle.equals(bundle) && Boolean.TRUE.toString()
                .equals(bundle.getHeaders().get("Vaadin-OSGi-Extender"));
    }

    private void handleFlowServerClassError(String className,
            Throwable throwable) {
        LoggerFactory.getLogger(VaadinBundleTracker.class)
                .trace("Couldn't load class '{}'", className, throwable);
    }

    private void handleBundleClassError(String className, Throwable throwable) {
        LoggerFactory.getLogger(VaadinBundleTracker.class)
                .warn("Couldn't load class '{}'", className, throwable);
    }

    private void scanClasses(Bundle bundle, Map<Long, Collection<Class<?>>> map,
            BiConsumer<String, Throwable> throwableHandler) {
        BundleWiring wiring = bundle.adapt(BundleWiring.class);

        // get all .class resources of this bundle
        Collection<String> classes = wiring.listResources("/", "*.class",
                BundleWiring.LISTRESOURCES_RECURSE);

        Collection<Class<?>> bundleClasses = new ArrayList<>();

        for (String clazz : classes) {
            String className = clazz.replaceAll("\\.class$", "").replace('/',
                    '.');
            if (bundle.equals(flowServerBundle)
                    && !className.startsWith("com.vaadin")) {
                continue;
            }
            if ("module-info".equals(className)) {
                // New modular Java info class which we are not intrested in
                continue;
            }
            try {
                bundleClasses.add(bundle.loadClass(className));
            } catch (ClassNotFoundException | NoClassDefFoundError exception) {
                throwableHandler.accept(className, exception);
            }
        }
        map.put(bundle.getBundleId(), bundleClasses);
    }

}
