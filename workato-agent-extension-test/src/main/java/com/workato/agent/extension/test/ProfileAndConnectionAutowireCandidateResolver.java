/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.TypeDescriptor;

import java.util.function.Supplier;

/**
 * Hooks into {@code @Value} dependency resolution to make {@code @Value Supplier<T>} targets
 * (fields, constructor parameters, setter method parameters) visible to <em>both</em> the profile
 * section and the connection payload — the connection payload is not in the
 * {@link org.springframework.core.env.Environment}, so Spring's default placeholder resolution
 * would otherwise only reach the profile.
 *
 * <p>Plain {@code @Value T} dependencies fall through unchanged, which is exactly the visibility
 * rule the harness enforces: plain {@code @Value} on a bean field sees only the profile. The
 * {@code Supplier<T>} shape is the opt-in signal that the caller wants late-binding visibility
 * across both sources.
 *
 * <p>Collision rule (profile wins, ERROR log) is applied inside
 * {@link PropertyResolution#resolve(String, TypeDescriptor)}; this resolver only decides when to
 * delegate there vs. when to let Spring handle the resolution natively.
 */
final class ProfileAndConnectionAutowireCandidateResolver extends ContextAnnotationAutowireCandidateResolver {

    private final PropertyResolution resolution;

    ProfileAndConnectionAutowireCandidateResolver(PropertyResolution resolution) {
        this.resolution = resolution;
    }

    private static TypeDescriptor supplierInnerType(ResolvableType supplierType) {
        ResolvableType inner = supplierType.getGeneric(0);
        Class<?> resolved = inner.resolve();
        return TypeDescriptor.valueOf(resolved == null ? String.class : resolved);
    }

    @Override
    public Object getSuggestedValue(DependencyDescriptor descriptor) {
        Object parent = super.getSuggestedValue(descriptor);
        if (!(parent instanceof String expression)) {
            return parent;
        }
        ResolvableType type = descriptor.getResolvableType();
        if (!Supplier.class.equals(type.getRawClass())) {
            // Plain @Value: hand the expression back to Spring untouched — the Environment has
            // only the profile source, so plain fields are profile-only by construction.
            return parent;
        }
        TypeDescriptor innerType = supplierInnerType(type);
        return (Supplier<Object>) () -> resolution.resolve(expression, innerType);
    }
}
