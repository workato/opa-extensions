/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentExtensionContextV1;
import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import com.workato.agent.extension.api.AgentExtensionV1;
import com.workato.agent.extension.api.AgentWebhookV1;

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

    FakeAgentExtension(String profileName, Map<String, Object> properties) {
        this(profileName, properties, new FakeAgentWebhookV1());
    }

    FakeAgentExtension(String profileName, Map<String, Object> properties, FakeAgentWebhookV1 webhook) {
        this.context = new FakeContext(profileName, properties);
        this.webhook = webhook;
    }

    private static AgentConfiguration unmodifiable(Map<String, Object> source) {
        Map<String, Object> copy = source == null ? Map.of() : new LinkedHashMap<>(source);
        return new UnmodifiableConfiguration(Collections.unmodifiableMap(copy));
    }

    @Override
    public AgentExtensionContextV1 getContext() {
        return context;
    }

    @Override
    public AgentWebhookV1 webhook() {
        return webhook;
    }

    FakeAgentWebhookV1 fakeWebhookApi() {
        return webhook;
    }

    private record FakeContext(String profileName,
                               AgentConfiguration configuration) implements AgentExtensionContextV1 {

        FakeContext(String profileName, Map<String, Object> properties) {
            this(profileName, unmodifiable(properties));
        }

        @Override
        public String getProfileName() {
            return profileName;
        }

        @Override
        public AgentConfiguration getProfileConfiguration() {
            return configuration;
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
}
