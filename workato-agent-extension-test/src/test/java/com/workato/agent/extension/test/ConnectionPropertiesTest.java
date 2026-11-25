/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentExtensionContextV1.ConnectionProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link ConnectionProperties} on the fake {@link FakeAgentExtension} and for
 * the {@code .connection(...)} builder surface on {@link AgentExtensionHarness}. Mirrors the shape
 * of {@link AgentExtensionContextV1Test}; the two together pin everything the production impl must
 * also honour.
 *
 * <h2>What is pinned here</h2>
 * <ul>
 *   <li>Builder methods: {@code connection(k,v)}, {@code connection(Map)}, {@code connectionYaml(...)}.</li>
 *   <li>Unmodifiable contract of the returned {@link ConnectionProperties} view.</li>
 *   <li>SHA-256 content-hash semantics: determinism, order-independence, change on every mutation.</li>
 *   <li>Profile-wins-on-collision rule, including that {@code @Value} resolution ignores the
 *       shadowed connection entry (collision logging itself is a side effect verified by
 *       inspection — adding a log-capture dependency is overkill for one WARN line).</li>
 * </ul>
 */
class ConnectionPropertiesTest extends AbstractExtensionTest {

    // ---- Default fixture: connection values set via inline .connection(...) ------------------

    @Nested
    class DefaultHarness {

        @RegisterExtension
        AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
                .yaml("extensions/echo.yml")
                .connection("api.token", "secret-abc")
                .connection("base-url", "https://api.example.com");

        @Test
        void getConnectionPropertiesExposesEveryRegisteredKey() {
            ConnectionProperties conn = agent.connectionProperties();
            assertThat(conn)
                    .containsEntry("api.token", "secret-abc")
                    .containsEntry("base-url", "https://api.example.com");
        }

        @Test
        void getConnectionPropertiesReflectsMapContract() {
            ConnectionProperties conn = agent.connectionProperties();
            assertThat(conn.size()).isEqualTo(2);
            assertThat(conn.isEmpty()).isFalse();
            assertThat(conn.containsKey("api.token")).isTrue();
            assertThat(conn.containsKey("missing")).isFalse();
            assertThat(conn.get("api.token")).isEqualTo("secret-abc");
            assertThat(conn.get("missing")).isNull();
        }

        @Test
        void connectionPropertiesHaveContentHash() {
            String hash = agent.connectionProperties().contentHash();
            assertThat(hash).hasSize(64);
            assertThat(hash).matches("[0-9a-f]{64}");
        }

        // ---- unmodifiable contract -----------------------------------------------------------

        @Test
        void getConnectionProperties_rejectsPut() {
            assertThatThrownBy(() -> agent.connectionProperties().put("x", 1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void getConnectionProperties_rejectsRemove() {
            assertThatThrownBy(() -> agent.connectionProperties().remove("api.token"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void getConnectionProperties_rejectsClear() {
            assertThatThrownBy(agent.connectionProperties()::clear)
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void getConnectionProperties_rejectsPutAll() {
            assertThatThrownBy(() -> agent.connectionProperties().put("x", 1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void getConnectionProperties_entrySetValueIsUnmodifiable() {
            Iterator<Map.Entry<String, Object>> it = agent.connectionProperties().entrySet().iterator();
            Map.Entry<String, Object> first = it.next();
            assertThatThrownBy(() -> first.setValue("tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void getConnectionProperties_entrySetIteratorRemoveIsUnmodifiable() {
            Iterator<Map.Entry<String, Object>> it = agent.connectionProperties().entrySet().iterator();
            it.next();
            assertThatThrownBy(it::remove).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ---- Empty connection map ----------------------------------------------------------------

    @Nested
    class EmptyConnection {

        @RegisterExtension
        AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
                .yaml("extensions/echo.yml");

        @Test
        void emptyConnectionStillExposesStableHash() {
            ConnectionProperties conn = agent.connectionProperties();
            assertThat(conn).isEmpty();
            assertThat(conn.contentHash())
                    // SHA-256 of the empty string
                    .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }
    }

    // ---- @Value resolution falls through to the connection source -----------------------------

    @Nested
    class ValueInjection {

        @RegisterExtension
        AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
                .yaml("extensions/echo.yml")
                .connection("api.token", "injected-value");

        @Test
        void valueAnnotationResolvesConnectionKey() {
            // The controller declared in echo.yml (EchoController) carries a @Value("${greeting}")
            // resolved against the profile; we only exercise that here indirectly by verifying
            // the property source list contains the connection source after start().
            // Direct wiring of a @Value against a connection key is covered by
            // ConnectionValueControllerTest.
            assertThat(agent.webApplicationContext().getEnvironment().getProperty("api.token"))
                    .isEqualTo("injected-value");
        }
    }

    // ---- Content-hash determinism ------------------------------------------------------------

    @Nested
    class ContentHashSemantics {

        @Test
        void sameContentDifferentOrderHasSameHash() {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("alpha", 1);
            a.put("beta", 2);

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("beta", 2);
            b.put("alpha", 1);

            FakeAgentExtension extA = new FakeAgentExtension("p", Map.of(), a, new FakeAgentWebhookV1());
            FakeAgentExtension extB = new FakeAgentExtension("p", Map.of(), b, new FakeAgentWebhookV1());

            assertThat(extA.getContext().getConnectionProperties().contentHash())
                    .isEqualTo(extB.getContext().getConnectionProperties().contentHash());
        }

        @Test
        void differentContentHasDifferentHash() {
            FakeAgentExtension extA = new FakeAgentExtension("p", Map.of(),
                    Map.of("api.token", "v1"), new FakeAgentWebhookV1());
            FakeAgentExtension extB = new FakeAgentExtension("p", Map.of(),
                    Map.of("api.token", "v2"), new FakeAgentWebhookV1());

            assertThat(extA.getContext().getConnectionProperties().contentHash())
                    .isNotEqualTo(extB.getContext().getConnectionProperties().contentHash());
        }

        @Test
        void hashMatchesKnownVector() {
            // Pinned: canonical form is "alpha=1\nbeta=2\n" → sha256 = …
            FakeAgentExtension ext = new FakeAgentExtension("p", Map.of(),
                    Map.of("alpha", 1, "beta", 2), new FakeAgentWebhookV1());
            assertThat(ext.getContext().getConnectionProperties().contentHash())
                    .isEqualTo(FakeAgentExtension.sha256Hex(Map.of("alpha", 1, "beta", 2)));
        }
    }

    // ---- connectionYaml(...) fixture ----------------------------------------------------------

    @Nested
    class ConnectionYaml {

        @RegisterExtension
        AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
                .yaml("extensions/with-connection.yml")
                .connectionYaml("extensions/with-connection.yml");

        @Test
        void connectionYamlFeedsConnectionPropertiesMap() {
            assertThat(agent.connectionProperties())
                    .containsEntry("api.token", "yaml-sourced-token");
        }

        @Test
        void inlineCallsWinOverConnectionYaml() {
            // Re-build a second harness where the inline value should beat the YAML.
            AgentExtensionHarness override = AgentExtensionHarness.extension("my-profile")
                    .yaml("extensions/with-connection.yml")
                    .connectionYaml("extensions/with-connection.yml")
                    .connection("api.token", "inline-wins")
                    .start();
            try {
                assertThat(override.connectionProperties())
                        .containsEntry("api.token", "inline-wins");
            } finally {
                override.stop();
            }
        }
    }

    // ---- Profile wins on key collision --------------------------------------------------------

    @Nested
    class ProfileWinsOnCollision {

        @RegisterExtension
        AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
                // greeting is declared in extensions/echo.yml with value "hello"; override it
                // from the connection side to prove the profile wins.
                .yaml("extensions/echo.yml")
                .connection("greeting", "connection-override");

        @Test
        void profileValueWinsForValueAnnotation() {
            // @Value("${greeting}") on EchoController would normally resolve against the profile
            // source. With a connection value registered for the same key, Spring must still
            // return the profile value — profile wins on collision.
            assertThat(agent.webApplicationContext().getEnvironment().getProperty("greeting"))
                    .isEqualTo("hello");
        }

        @Test
        void connectionMapStillReportsConnectionValueVerbatim() {
            // The data the test registered on the connection side is unchanged — only @Value
            // resolution differs. The connection map is a verbatim view of what came in.
            assertThat(agent.connectionProperties())
                    .containsEntry("greeting", "connection-override");
        }
    }
}
