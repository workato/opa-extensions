/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.function.Supplier;

/**
 * Handles {@code @Value} on handler-method parameters so both the profile section and the
 * connection payload are visible — unlike the default
 * {@code ExpressionValueMethodArgumentResolver}, which only sees whatever is in the
 * {@code Environment}'s {@code PropertySources} (profile only, by harness design).
 *
 * <p>Registered via {@link ArgumentResolverRegistrar} ahead of Spring's defaults so this resolver
 * owns {@code @Value} on handler parameters. The default behaviour is otherwise preserved for
 * all non-{@code @Value} parameters.
 *
 * <h2>Supported parameter shapes</h2>
 * <ul>
 *   <li>Plain type: resolved + converted via {@link PropertyResolution#resolve}.</li>
 *   <li>{@code Supplier<T>}: wrapped so {@code get()} returns the resolved value — captures the
 *       lookup at resolve-time rather than at each call; the harness's profile and connection
 *       views are themselves immutable so re-evaluation would yield the same answer.</li>
 * </ul>
 */
final class ProfileAndConnectionValueArgumentResolver implements HandlerMethodArgumentResolver {

    private final PropertyResolution resolution;

    ProfileAndConnectionValueArgumentResolver(PropertyResolution resolution) {
        this.resolution = resolution;
    }

    private static TypeDescriptor supplierInnerType(MethodParameter parameter) {
        ResolvableType generic = ResolvableType.forMethodParameter(parameter).getGeneric(0);
        Class<?> resolved = generic.resolve();
        return TypeDescriptor.valueOf(resolved == null ? String.class : resolved);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Value.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Value annotation = parameter.getParameterAnnotation(Value.class);
        String expression = annotation == null ? null : annotation.value();
        if (expression == null) {
            return null;
        }
        if (Supplier.class.equals(parameter.getParameterType())) {
            TypeDescriptor innerType = supplierInnerType(parameter);
            Object resolved = resolution.resolve(expression, innerType);
            return (Supplier<Object>) () -> resolved;
        }
        TypeDescriptor targetType = new TypeDescriptor(parameter);
        return resolution.resolve(expression, targetType);
    }
}
