/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentExtensionContextV1;
import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import com.workato.agent.extension.api.AgentExtensionContextV1.ConnectionProperties;
import com.workato.agent.extension.api.AgentExtensionV1;
import com.workato.agent.extension.api.AgentToolsV1;
import com.workato.agent.extension.api.AgentWebhookV1;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Fake {@link AgentExtensionV1} suitable for standalone unit / integration tests of a customer
 * extension. Carries the profile name and the YAML-shaped configuration the test supplies to
 * the {@link AgentExtensionHarness} builder.
 *
 * <p>The configuration view returned by {@link AgentExtensionContextV1#getProfileConfiguration()}
 * is intentionally built with the <em>same</em> unmodifiable semantics as the agent's production
 * implementation (defensive copy wrapped in {@code Collections.unmodifiableMap}; mutations
 * throw {@code UnsupportedOperationException}). A controller that accidentally mutates
 * {@code getProfileConfiguration()} fails in the test rather than silently drifting from
 * production behaviour.
 */
final class FakeAgentExtension implements AgentExtensionV1 {

    private final AgentExtensionContextV1 context;
    private final FakeAgentWebhookV1 webhook;
    private final AgentToolsV1 tools;

    FakeAgentExtension(String profileName, Map<String, Object> properties) {
        this(profileName, properties, Map.of(), new FakeAgentWebhookV1());
    }

    FakeAgentExtension(String profileName,
                       Map<String, Object> properties,
                       Map<String, Object> connectionProperties,
                       FakeAgentWebhookV1 webhook) {
        this.context = new FakeContext(profileName, properties, connectionProperties);
        this.webhook = webhook;
        this.tools = new FakeTools(JsonMapper.builder().build());
    }

    private static AgentConfiguration unmodifiableConfiguration(Map<String, Object> source) {
        Map<String, Object> copy = source == null ? Map.of() : new LinkedHashMap<>(source);
        return new UnmodifiableConfiguration(Collections.unmodifiableMap(copy));
    }

    private static ConnectionProperties unmodifiableConnection(Map<String, Object> source) {
        Map<String, Object> copy = source == null ? Map.of() : new LinkedHashMap<>(source);
        return new UnmodifiableConnectionProperties(Collections.unmodifiableMap(copy));
    }

    static String sha256Hex(Map<String, Object> entries) {
        StringBuilder canonical = new StringBuilder();
        List<String> sortedKeys = new ArrayList<>(entries.keySet());
        Collections.sort(sortedKeys);
        for (String key : sortedKeys) {
            canonical.append(key).append('=').append(entries.get(key)).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    @Override
    public AgentExtensionContextV1 getContext() {
        return context;
    }

    @Override
    public AgentWebhookV1 webhook() {
        return webhook;
    }

    @Override
    public AgentToolsV1 getTools() {
        return tools;
    }

    FakeAgentWebhookV1 fakeWebhookApi() {
        return webhook;
    }

    private record FakeTools(ObjectMapper objectMapper) implements AgentToolsV1 {
    }

    private record FakeContext(String profileName,
                               AgentConfiguration configuration,
                               ConnectionProperties connection) implements AgentExtensionContextV1 {

        FakeContext(String profileName, Map<String, Object> properties, Map<String, Object> connectionProperties) {
            this(profileName, unmodifiableConfiguration(properties), unmodifiableConnection(connectionProperties));
        }

        @Override
        public String getProfileName() {
            return profileName;
        }

        @Override
        public AgentConfiguration getProfileConfiguration() {
            return configuration;
        }

        @Override
        public ConnectionProperties getConnectionProperties() {
            return connection;
        }
    }

    private static final class UnmodifiableConfiguration extends AbstractMap<String, Object> implements AgentConfiguration {

        private final Map<String, Object> delegate;

        UnmodifiableConfiguration(Map<String, Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return delegate.entrySet();
        }
    }

    private static final class UnmodifiableConnectionProperties extends AbstractMap<String, Object>
            implements ConnectionProperties {

        private final Map<String, Object> delegate;
        private final String contentHash;

        UnmodifiableConnectionProperties(Map<String, Object> delegate) {
            this.delegate = delegate;
            this.contentHash = sha256Hex(delegate);
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return delegate.entrySet();
        }

        @Override
        public String contentHash() {
            return contentHash;
        }
    }
}
