/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link PropertySource} that resolves {@code @Value("${key}")} on extension controllers against
 * the properties the test supplied to {@link AgentExtensionHarness#property(String, Object)} or
 * {@link AgentExtensionHarness#connection(String, Object)}.
 *
 * <p>Mirrors the agent's production {@code com.workato.agent.extension.EndpointPropertySource} 1-to-1
 * in two load-bearing details:
 *
 * <ul>
 *   <li><b>Direct map lookup.</b> {@link #getProperty(String)} is a plain {@code map.get(name)}
 *       — dots in keys are literal characters, not nesting separators. A YAML section with
 *       {@code mail.pop3.username: foo} answers {@code @Value("${mail.pop3.username}")} with
 *       {@code "foo"}, not with a traversal into a nested map.</li>
 *   <li><b>PropertySource name {@code "{profile}PropertySource"} (profile source) or
 *       {@code "{profile}ConnectionPropertySource"} (connection source).</b> At least one real
 *       customer pattern locates the profile PropertySource by class and parses the profile name
 *       out of the source name (strip the {@code "PropertySource"} suffix). Changing either the
 *       class name or the suffix on the profile source is a breaking change for those customers;
 *       the harness therefore keeps the exact same convention so such patterns pass the test as
 *       they would in production. The connection source uses a distinct suffix so the same
 *       strip-suffix trick keeps working.</li>
 * </ul>
 */
final class EndpointPropertySource extends PropertySource<Map<String, Object>> {

    private EndpointPropertySource(String sourceName, Map<String, Object> properties) {
        super(sourceName, new LinkedHashMap<>(properties == null ? Map.of() : properties));
    }

    static EndpointPropertySource forProfile(String profileName, Map<String, Object> properties) {
        return new EndpointPropertySource(profileName + "PropertySource", properties);
    }

    static EndpointPropertySource forConnection(String profileName, Map<String, Object> properties) {
        return new EndpointPropertySource(profileName + "ConnectionPropertySource", properties);
    }

    @Override
    public Object getProperty(String name) {
        return getSource().get(name);
    }
}
