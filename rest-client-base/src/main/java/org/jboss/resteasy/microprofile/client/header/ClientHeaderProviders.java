/*
 * JBoss, Home of Professional Open Source.
 *
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.resteasy.microprofile.client.header;

import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import org.eclipse.microprofile.rest.client.RestClientDefinitionException;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;
import org.eclipse.microprofile.rest.client.ext.DefaultClientHeadersFactoryImpl;
import org.jboss.resteasy.cdi.CdiConstructorInjector;

/**
 * A storage of {@link ClientHeaderProvider}s
 */
public class ClientHeaderProviders {
    private static final ClientHeadersFactory defaultHeadersFactory = new DefaultClientHeadersFactoryImpl();

    private static final Map<Method, ClientHeaderProvider> providersForMethod = new ConcurrentHashMap<>();
    private static final Map<Class<?>, ClientHeadersFactory> headerFactoriesForClass = new ConcurrentHashMap<>();

    private static final HeaderFillerFactory fillerFactory;

    /**
     * Get {@link ClientHeaderProvider} for a given method, if exists
     *
     * @param method a method to get the provider for
     *
     * @return the provider responsible for setting the headers
     */
    public static Optional<ClientHeaderProvider> getProvider(Method method) {
        return Optional.ofNullable(providersForMethod.get(method));
    }

    /**
     * Get {@link ClientHeadersFactory} for a given class, if exists
     *
     * @param aClass a class to get the ClientHeadersFactory for
     *
     * @return the factory used to adjust the headers
     */
    public static Optional<ClientHeadersFactory> getFactory(Class<?> aClass) {
        return Optional.ofNullable(headerFactoriesForClass.get(aClass));
    }

    /**
     * Register, in a static map, {@link ClientHeaderProvider}`s for the given class and all of its methods
     *
     * @param clientClass a class to scan for {@link org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam} and
     *                    {@link RegisterClientHeaders}
     * @param clientProxy proxy of the clientClass, used to handle the default methods
     *
     * @deprecated use {@link #registerForClass(Class, Object, BeanManager)}
     */
    @Deprecated
    public static void registerForClass(Class<?> clientClass, Object clientProxy) {
        registerForClass(clientClass, clientProxy, null);
    }

    /**
     * Register, in a static map, {@link ClientHeaderProvider}`s for the given class and all of its methods
     *
     * @param clientClass a class to scan for {@link org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam} and
     *                    {@link RegisterClientHeaders}
     * @param clientProxy proxy of the clientClass, used to handle the default methods
     * @param beanManager the bean manager used to construct CDI beans
     */
    public static void registerForClass(Class<?> clientClass, Object clientProxy, BeanManager beanManager) {
        Stream.of(clientClass.getMethods())
                .forEach(m -> registerForMethod(m, clientProxy));
        registerHeaderFactory(clientClass, beanManager);
    }

    private static void registerHeaderFactory(Class<?> aClass, BeanManager beanManager) {
        RegisterClientHeaders annotation = aClass.getAnnotation(RegisterClientHeaders.class);
        if (annotation != null) {
            Optional<ClientHeadersFactory> clientHeadersFactory = getCustomHeadersFactory(annotation, aClass, beanManager);

            headerFactoriesForClass.put(aClass, clientHeadersFactory.orElse(defaultHeadersFactory));
        }
    }

    private static Optional<ClientHeadersFactory> getCustomHeadersFactory(RegisterClientHeaders annotation,
            Class<?> source, BeanManager beanManager) {
        Class<? extends ClientHeadersFactory> factoryClass = annotation.value();
        try {
            return Optional.of(construct(factoryClass, beanManager));
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RestClientDefinitionException(
                    "Failed to instantiate " + factoryClass.getCanonicalName() + ", the client header factory for "
                            + source.getCanonicalName(),
                    e);
        }
    }

    private static void registerForMethod(Method method, Object clientProxy) {
        ClientHeaderProvider.forMethod(method, clientProxy, fillerFactory).ifPresent(
                provider -> providersForMethod.put(method, provider));
    }

    private static ClientHeadersFactory construct(final Class<? extends ClientHeadersFactory> factory,
            final BeanManager manager)
            throws IllegalAccessException, InstantiationException {
        if (manager != null) {
            Set<Bean<?>> beans = manager.getBeans(factory);
            if (!beans.isEmpty()) {
                final CdiConstructorInjector injector = new CdiConstructorInjector(factory, manager);
                // The CdiConstructorInjector does not use the unwrapAsync value using false has no effect
                return factory.cast(injector.construct(false));
            }
        }
        return factory.newInstance();
    }

    static {
        final PrivilegedAction<HeaderFillerFactory> action = () -> {
            ServiceLoader<HeaderFillerFactory> fillerFactories = ServiceLoader.load(HeaderFillerFactory.class);
            int highestPrio = Integer.MIN_VALUE;
            HeaderFillerFactory result = null;
            for (HeaderFillerFactory factory : fillerFactories) {
                if (factory.getPriority() > highestPrio) {
                    highestPrio = factory.getPriority();
                    result = factory;
                }
            }
            return result;
        };
        final HeaderFillerFactory result = System.getSecurityManager() == null ? action.run()
                : AccessController.doPrivileged(action);
        if (result == null) {
            throw new IllegalStateException("Unable to find a HeaderFillerFactory implementation");
        } else {
            fillerFactory = result;
        }
    }

    private ClientHeaderProviders() {
    }
}
