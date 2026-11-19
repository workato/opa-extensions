/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentExtensionContextV1;
import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import com.workato.agent.extension.api.AgentExtensionV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Iterator;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the fake {@link AgentExtensionV1} / {@link AgentExtensionContextV1} /
 * {@link AgentConfiguration} shipped by {@code extension-test}.
 *
 * <p>Serves as the single, discoverable place that pins the test-side mock behaves exactly as
 * the production implementation ({@code AgentExtensionV1Impl} in {@code agent-core}, pinned by
 * {@code AgentExtensionV1ImplTest} in {@code agent-launcher}'s test tree). Any customer who
 * wants to know "what guarantees does the fake make?" reads this file.
 *
 * <p>When extending the V1 API, add a corresponding assertion here <em>and</em> in the production
 * impl's own test — the two in lockstep are the executable guarantee that mock and prod stay in
 * sync. See the warning block in {@code extension-api/CLAUDE.md}.
 */
class AgentExtensionContextV1Test extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
            .yaml("extensions/echo.yml");

    private static AgentExtensionContextV1 context() {
        return agent.agentExtension().getContext();
    }

    private static AgentConfiguration configuration() {
        return context().getProfileConfiguration();
    }

    // ---- AgentExtensionV1 --------------------------------------------------------------------

    @Test
    void agentExtensionBeanIsRegistered() {
        AgentExtensionV1 api = agent.agentExtension();
        assertThat(api).isNotNull();
        assertThat(api.getContext()).isNotNull();
    }

    // ---- AgentExtensionContextV1.getProfileName() -----------------------------------------------

    @Test
    void getProfileNameReturnsTheConfiguredProfile() {
        assertThat(context().getProfileName()).isEqualTo("my-profile");
    }

    // ---- AgentExtensionContextV1.getProfileConfiguration() --------------------------------------

    @Test
    void getProfileConfigurationExposesEveryYamlKey() {
        // extensions/echo.yml declares: controller, greeting, custom-number
        assertThat(configuration())
                .containsEntry("greeting", "hello")
                .containsEntry("custom-number", 42)
                .containsEntry("controller",
                        "com.workato.agent.extension.test.testext.EchoController");
    }

    @Test
    void getProfileConfigurationReflectsMapContract() {
        AgentConfiguration cfg = configuration();
        assertThat(cfg.size()).isEqualTo(3);
        assertThat(cfg.isEmpty()).isFalse();
        assertThat(cfg.containsKey("greeting")).isTrue();
        assertThat(cfg.containsKey("not-declared")).isFalse();
        assertThat(cfg.containsValue("hello")).isTrue();
        assertThat(cfg.get("greeting")).isEqualTo("hello");
        assertThat(cfg.get("missing")).isNull();
    }

    @Test
    void getProfileConfigurationIteratesInInsertionOrder() {
        // LinkedHashMap-backed under the hood, consistent with production.
        assertThat(configuration().keySet())
                .containsExactly("controller", "greeting", "custom-number");
    }

    // ---- AgentConfiguration unmodifiable contract — direct mutators ----------------------------

    @Test
    void getProfileConfiguration_rejectsPut() {
        assertThatThrownBy(() -> configuration().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getProfileConfiguration_rejectsRemove() {
        assertThatThrownBy(() -> configuration().remove("greeting"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getProfileConfiguration_rejectsClear() {
        assertThatThrownBy(configuration()::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getProfileConfiguration_rejectsPutAll() {
        assertThatThrownBy(() -> configuration().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- AgentConfiguration unmodifiable contract — view mutators ------------------------------

    @Test
    void getProfileConfiguration_entrySetIsUnmodifiable() {
        assertThatThrownBy(() -> configuration().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getProfileConfiguration_entrySetIteratorRemoveIsUnmodifiable() {
        Iterator<Map.Entry<String, Object>> it = configuration().entrySet().iterator();
        it.next();
        assertThatThrownBy(it::remove).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getProfileConfiguration_entrySetValueIsUnmodifiable() {
        // Entry.setValue() is the subtle attack vector that pure LinkedHashMap subclassing misses;
        // the fake must plug it the same way production does — via Collections.unmodifiableMap.
        Map.Entry<String, Object> entry = configuration().entrySet().iterator().next();
        assertThatThrownBy(() -> entry.setValue("tampered"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getProfileConfiguration_keySetIsUnmodifiable() {
        assertThatThrownBy(() -> configuration().remove("greeting"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getProfileConfiguration_valuesAreUnmodifiable() {
        assertThatThrownBy(() -> configuration().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
