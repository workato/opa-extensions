/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link PropertySource} that resolves {@code @Value("${key}")} on extension controllers against
 * the properties the test supplied to {@link AgentExtensionHarness#property(String, Object)}.
 *
 * <p>Mirrors the agent's production {@code com.workato.agent.extension.EndpointPropertySource} 1-to-1
 * in two load-bearing details:
 *
 * <ul>
 *   <li><b>Direct map lookup.</b> {@link #getProperty(String)} is a plain {@code map.get(name)}
 *       — dots in keys are literal characters, not nesting separators. A YAML section with
 *       {@code mail.pop3.username: foo} answers {@code @Value("${mail.pop3.username}")} with
 *       {@code "foo"}, not with a traversal into a nested map.</li>
 *   <li><b>PropertySource name {@code "{profile}PropertySource"}.</b> At least one real
 *       customer pattern locates this PropertySource by class and parses the profile name out of
 *       the source name (strip the {@code "PropertySource"} suffix). Changing either the class
 *       name or the suffix is a breaking change for those customers; the harness therefore keeps
 *       the exact same convention so such patterns pass the test as they would in production.</li>
 * </ul>
 */
final class EndpointPropertySource extends PropertySource<Map<String, Object>> {

    EndpointPropertySource(String profileName, Map<String, Object> properties) {
        super(profileName + "PropertySource", new LinkedHashMap<>(properties == null ? Map.of() : properties));
    }

    @Override
    public Object getProperty(String name) {
        return getSource().get(name);
    }
}
