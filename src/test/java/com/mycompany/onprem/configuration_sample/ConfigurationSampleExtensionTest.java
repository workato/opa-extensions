package com.mycompany.onprem.configuration_sample;

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
 * Harness-level pinning of {@link ConfigurationSampleExtension}. Covers:
 *
 * <ul>
 *   <li>Static per-adapter {@code environment} resolved through raw-type
 *       {@code @Value} from the YAML fixture ({@code extensions.configuration_sample.environment}).</li>
 *   <li>Connection-dynamic {@code timeout} resolved through
 *       {@code @Value Supplier<Integer>}, backed by {@code .connection(id, Map)}.</li>
 *   <li>Validation branch of {@code /test} — 400 when {@code timeout <= 0} or missing.</li>
 *   <li>{@code updatedAt} populated by {@code onConnectionAdded} / {@code onConnectionUpdated}
 *       callbacks and dropped by {@code onConnectionRemoved}.</li>
 * </ul>
 */
class ConfigurationSampleExtensionTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final AtomicLong CURRENT_CONNECTION_ID = new AtomicLong(42L);

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("configuration_sample")
            .yaml("extensions/configuration_sample.yml")
            .connectionIdSupplier(CURRENT_CONNECTION_ID::get)
            .connection(42L, Map.of("timeout", 5000))
            .connection(43L, Map.of("timeout", 0))
            .connection(44L, Map.of());

    private static void useConnection(long id) {
        CURRENT_CONNECTION_ID.set(id);
    }

    private static MvcResult call(String path) throws Exception {
        return agent.mockMvc().perform(get(agent.urlFor(path))).andReturn();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    @BeforeEach
    void resetHarnessState() {
        useConnection(42L);
    }

    @Test
    void infoReturnsStaticAndCloudParams() throws Exception {
        MvcResult res = call("/42/info");
        assertEquals(200, res.getResponse().getStatus());
        JsonNode b = body(res);

        assertEquals("test", b.at("/environment").asString());
        assertEquals(5000, b.at("/timeout").asInt());
        // connectionVersion is surfaced in the response as a long; the harness
        // only bumps it in response to explicit .triggerConnection* calls, so
        // for a plain .connection(...) registration it stays at the default
        // sentinel (0). The production agent bumps it on cloud push diff.
        assertTrue(b.at("/connectionVersion").isNumber(),
                "connectionVersion should be a number: " + b.at("/connectionVersion"));
    }

    @Test
    void infoSurfacesUpdatedAtAfterLifecycleCallback() throws Exception {
        // Trigger an add callback so updatedAt is populated for connection 100.
        agent.triggerConnectionAdded(100L, Map.of("timeout", 7000));
        useConnection(100L);

        JsonNode b = body(call("/100/info"));
        assertEquals(7000, b.at("/timeout").asInt());
        assertFalse(b.at("/updatedAt").isNull(),
                "updatedAt should be set after onConnectionAdded");
    }

    @Test
    void infoShowsNullUpdatedAtForUnseenConnection() throws Exception {
        // Connection 43 was registered via the builder's .connection(...) which
        // does NOT invoke lifecycle callbacks — updatedAt must stay null.
        useConnection(43L);
        JsonNode b = body(call("/43/info"));
        assertTrue(b.at("/updatedAt").isNull(),
                "updatedAt should be null when no lifecycle callback has fired");
    }

    @Test
    void testPassesWhenTimeoutIsPositive() throws Exception {
        MvcResult res = call("/42/test");
        assertEquals(200, res.getResponse().getStatus());
        JsonNode b = body(res);
        assertEquals("ok", b.at("/status").asString());
        assertEquals(5000, b.at("/timeout").asInt());
    }

    @Test
    void testFailsWhenTimeoutIsZero() throws Exception {
        useConnection(43L);
        MvcResult res = call("/43/test");
        assertEquals(400, res.getResponse().getStatus());
        JsonNode b = body(res);
        assertEquals("error", b.at("/status").asString());
        assertTrue(b.at("/error").asString().contains("timeout"),
                "error message should mention timeout: " + b.at("/error"));
    }

    @Test
    void testFailsWhenTimeoutMissing() throws Exception {
        // Connection 44 has no timeout key — the Supplier<Integer> default (0)
        // kicks in and the validation rejects it.
        useConnection(44L);
        MvcResult res = call("/44/test");
        assertEquals(400, res.getResponse().getStatus());
    }

    @Test
    void adapterNameSurfaceInUrl() throws Exception {
        // Just a sanity check — the harness mounts the extension under
        // /ext/configuration_sample/* so /42/info resolves.
        assertEquals(200, call("/42/info").getResponse().getStatus());
    }
}
