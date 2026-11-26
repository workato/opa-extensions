/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Prepends {@link ProfileAndConnectionValueArgumentResolver} to the {@link RequestMappingHandlerAdapter}'s
 * resolver list after the adapter finishes {@code afterPropertiesSet()}. Needed because
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer#addArgumentResolvers}
 * appends customs <em>after</em> the built-in {@code ExpressionValueMethodArgumentResolver}, which
 * would otherwise claim {@code @Value} handler params first and bypass the connection source.
 */
final class ArgumentResolverRegistrar implements BeanPostProcessor {

    private final ProfileAndConnectionValueArgumentResolver resolver;

    ArgumentResolverRegistrar(ProfileAndConnectionValueArgumentResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof RequestMappingHandlerAdapter adapter)) {
            return bean;
        }
        List<HandlerMethodArgumentResolver> current = adapter.getArgumentResolvers();
        if (current == null) {
            return bean;
        }
        List<HandlerMethodArgumentResolver> prepended = new ArrayList<>(current.size() + 1);
        prepended.add(resolver);
        prepended.addAll(current);
        adapter.setArgumentResolvers(prepended);
        return bean;
    }
}
