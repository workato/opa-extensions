package com.mycompany.onprem.subscriptions_sample;

import com.workato.agent.extension.test.AgentExtensionHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Harness-level pinning of {@link SubscriptionsSampleExtension}. Covers:
 *
 * <ul>
 *   <li>Static per-adapter keys resolved through raw-type {@code @Value} from
 *       the YAML fixture ({@code extensions.subscriptions_sample.*}).</li>
 *   <li>Connection-dynamic keys resolved both via {@code @Value Supplier<T>}
 *       and via {@code getProfileConfiguration().get(...)}, backed by the
 *       harness's {@code .connection(id, Map)} entries.</li>
 *   <li>Lifecycle callbacks ({@code AgentConnectionLifecycleV1}) triggered by
 *       the harness's {@code .triggerConnection*(...)} builder methods, with
 *       counters and per-connection history surfaced in the response.</li>
 * </ul>
 */
class SubscriptionsSampleExtensionTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Lets each test switch "the connection currently in flight" without
     * rebuilding the harness. Updated via {@link #useConnection(long)}.
     */
    private static final AtomicLong CURRENT_CONNECTION_ID = new AtomicLong(42L);

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("subscriptions_sample")
            .yaml("extensions/subscriptions_sample.yml")
            .connectionIdSupplier(CURRENT_CONNECTION_ID::get)
            .connection(42L, Map.of(
                    "label", "primary",
                    "host", "prod.example.com",
                    "port", 2242))
            .connection(43L, Map.of(
                    "host", "staging.example.com",
                    "port", 2243));

    private static void useConnection(long id) {
        CURRENT_CONNECTION_ID.set(id);
    }

    private static JsonNode inspect(long connId) throws Exception {
        MvcResult result = agent.mockMvc()
                .perform(get(agent.urlFor("/" + connId + "/inspect")))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "unexpected status, body=" + result.getResponse().getContentAsString());
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    @BeforeEach
    void resetHarnessState() {
        // Each test starts from connection 42; individual tests flip to 43 via
        // useConnection(43L) when they need to.
        useConnection(42L);
    }

    @Test
    void returnsStaticPerAdapterValuesFromYaml() throws Exception {
        JsonNode body = inspect(42L);

        assertEquals("test", body.at("/staticPerAdapter/environment").asString());
        assertEquals("eu-west-1", body.at("/staticPerAdapter/region").asString());
        assertEquals("subscriptions_sample-demo", body.at("/staticPerAdapter/bannerText").asString());
    }

    @Test
    void returnsConnectionValueThroughSupplierAndConfig() throws Exception {
        JsonNode body = inspect(42L);

        // Both resolution paths see the same connection-specific value.
        assertEquals("primary", body.at("/connection/label-via-supplier").asString());
        assertEquals("primary", body.at("/connection/label-via-config").asString());

        // getProfileConfiguration() exposes every key of the current connection,
        // including the merged static layer and the controller key the harness
        // forwards automatically. Just check the connection-specific ones here.
        var keys = body.at("/connection/allKeys");
        assertTrue(keys.toString().contains("\"host\""), "expected 'host' in: " + keys);
        assertTrue(keys.toString().contains("\"port\""), "expected 'port' in: " + keys);
        assertTrue(keys.toString().contains("\"label\""), "expected 'label' in: " + keys);
    }

    @Test
    void appliesSupplierDefaultWhenConnectionHasNoKey() throws Exception {
        // Connection 43 was registered without a `label` key — the ${label:unlabeled}
        // default on the controller field should fire.
        useConnection(43L);
        JsonNode body = inspect(43L);

        assertEquals("unlabeled", body.at("/connection/label-via-supplier").asString());
        assertEquals("null", body.at("/connection/label-via-config").asString(),
                "getProfileConfiguration().get(absent) is null; the controller stringifies it");
    }

    @Test
    void addedCallbackBumpsCounterAndTracksConnection() throws Exception {
        // Fresh connection id — the controller has not seen it before.
        agent.triggerConnectionAdded(100L, Map.of("label", "fresh"));

        useConnection(100L);
        JsonNode body = inspect(100L);

        assertTrue(body.at("/lifecycle/addedTotal").asLong() >= 1L,
                "addedTotal did not advance");
        assertTrue(body.at("/lifecycle/trackedConnections").toString().contains("100"),
                "100 missing from trackedConnections: "
                        + body.at("/lifecycle/trackedConnections"));
        assertEquals(0, body.at("/lifecycle/thisConnection/updateCount").asInt(),
                "update count should stay 0 right after onConnectionAdded");
    }

    @Test
    void updatedCallbackBumpsUpdateCounter() throws Exception {
        agent.triggerConnectionAdded(200L, Map.of("label", "v1"));
        agent.triggerConnectionUpdated(200L, Map.of("label", "v2", "extra", "x"));
        agent.triggerConnectionUpdated(200L, Map.of("label", "v3"));

        useConnection(200L);
        JsonNode body = inspect(200L);

        assertEquals(2, body.at("/lifecycle/thisConnection/updateCount").asInt(),
                "two triggerConnectionUpdated calls should produce updateCount == 2");
        assertTrue(body.at("/lifecycle/updatedTotal").asLong() >= 2L,
                "updatedTotal did not advance by at least 2");
    }

    @Test
    void removedCallbackDropsConnectionFromTracking() throws Exception {
        agent.triggerConnectionAdded(300L, Map.of("label", "ephemeral"));
        agent.triggerConnectionRemoved(300L);

        useConnection(300L);
        JsonNode body = inspect(300L);

        assertFalse(body.at("/lifecycle/trackedConnections").toString().contains("300"),
                "300 should have been dropped: "
                        + body.at("/lifecycle/trackedConnections"));
        assertTrue(body.at("/lifecycle/removedTotal").asLong() >= 1L,
                "removedTotal did not advance");
        assertEquals("never",
                body.at("/lifecycle/thisConnection/firstSeenAt").asString(),
                "removed → the connection falls back to the 'unseen' sentinel");
    }

    @Test
    void adapterNameAndPathIdSurfaceInResponse() throws Exception {
        useConnection(42L);
        JsonNode body = inspect(42L);

        assertEquals("subscriptions_sample", body.at("/adapter").asString());
        assertEquals(42L, body.at("/connectionIdFromPath").asLong());
        assertEquals(42L, body.at("/connectionId").asLong(),
                "fake's connectionIdSupplier should match the path when test aligns them");
    }

    @Test
    void supplierTracksCurrentConnection() throws Exception {
        // Same singleton controller, two different connections → two different
        // supplier.get() values. This is the whole point of Supplier<T> @Value in
        // a cloud-managed extension.
        useConnection(42L);
        assertEquals("primary", inspect(42L).at("/connection/label-via-supplier").asString());

        useConnection(43L);
        assertEquals("unlabeled", inspect(43L).at("/connection/label-via-supplier").asString());

        useConnection(42L);
        assertEquals("primary", inspect(42L).at("/connection/label-via-supplier").asString());
    }
}
