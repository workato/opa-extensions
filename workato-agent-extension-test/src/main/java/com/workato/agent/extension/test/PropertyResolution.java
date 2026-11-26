/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.Environment;

import java.util.Map;

/**
 * Shared resolution of {@code @Value("${key[:default]}")} expressions against the harness's two
 * property sources — the profile section (static YAML) and the connection payload (per-endpoint
 * secrets / URLs).
 *
 * <p>The visibility rule depends on the injection site, not on this resolver:
 * <ul>
 *   <li>Plain {@code @Value} on a bean field — Spring's default path through
 *       {@link Environment#resolvePlaceholders(String)} runs; the harness only puts the profile
 *       {@code PropertySource} in the {@link Environment}, so plain fields never see connection.</li>
 *   <li>{@code @Value Supplier<T>} on a bean field — handled by
 *       {@link SupplierValueFieldPostProcessor}, which calls into this resolver.</li>
 *   <li>Plain or {@code Supplier}-shaped {@code @Value} on a handler-method parameter — handled by
 *       {@link ProfileAndConnectionValueArgumentResolver}, which also calls into this resolver.</li>
 * </ul>
 *
 * <p>Collision rule: if the key exists in both the profile section and the connection payload,
 * the profile value wins and an {@code ERROR}-level log entry fires. Documented on
 * {@link com.workato.agent.extension.api.AgentExtensionContextV1#getConnectionProperties()}.
 */
final class PropertyResolution {

    private static final Logger LOG = LoggerFactory.getLogger(PropertyResolution.class);
    private static final ConversionService CONVERSION = DefaultConversionService.getSharedInstance();

    private final String profileName;
    private final Map<String, Object> profile;
    private final Map<String, Object> connection;

    PropertyResolution(String profileName, Map<String, Object> profile, Map<String, Object> connection) {
        this.profileName = profileName;
        this.profile = profile;
        this.connection = connection;
    }

    private static boolean isPlaceholder(String expr) {
        return expr != null && expr.startsWith("${") && expr.endsWith("}");
    }

    private static String parsePlaceholderKey(String expr) {
        if (!isPlaceholder(expr)) {
            return expr;
        }
        String inner = expr.substring(2, expr.length() - 1);
        int colon = inner.indexOf(':');
        return colon < 0 ? inner : inner.substring(0, colon);
    }

    private static String parsePlaceholderDefault(String expr) {
        if (!isPlaceholder(expr)) {
            return null;
        }
        String inner = expr.substring(2, expr.length() - 1);
        int colon = inner.indexOf(':');
        return colon < 0 ? null : inner.substring(colon + 1);
    }

    /**
     * Resolve a {@code @Value} expression and convert the result to {@code targetType}. The
     * expression must be a single placeholder {@code ${key}} or {@code ${key:default}}; literal
     * fallthrough is supported for completeness.
     *
     * @throws IllegalArgumentException if the placeholder has no value in either source and no
     *                                  default is supplied
     */
    Object resolve(String valueExpression, TypeDescriptor targetType) {
        String rawKey = parsePlaceholderKey(valueExpression);
        String defaultValue = parsePlaceholderDefault(valueExpression);
        Object rawValue;
        boolean profileHas = profile.containsKey(rawKey);
        boolean connectionHas = connection.containsKey(rawKey);
        if (profileHas && connectionHas) {
            LOG.error("Extension '{}': @Value('${}{}{}') resolved to the profile value — the "
                            + "connection payload also carries this key but profile properties always "
                            + "win on collision. Rename one of them.",
                    profileName, "{", rawKey, "}");
            rawValue = profile.get(rawKey);
        }
        else if (profileHas) {
            rawValue = profile.get(rawKey);
        }
        else if (connectionHas) {
            rawValue = connection.get(rawKey);
        }
        else if (defaultValue != null) {
            rawValue = defaultValue;
        }
        else if (isPlaceholder(valueExpression)) {
            throw new IllegalArgumentException(
                    "Could not resolve placeholder '" + rawKey + "' in @Value for extension '"
                            + profileName + "' — not present in profile section nor connection payload");
        }
        else {
            rawValue = valueExpression;   // literal
        }
        if (rawValue == null) {
            return null;
        }
        if (targetType.getObjectType().isInstance(rawValue)) {
            return rawValue;
        }
        return CONVERSION.convert(rawValue, TypeDescriptor.forObject(rawValue), targetType);
    }
}
