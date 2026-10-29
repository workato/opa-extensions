package com.mycompany.onprem.configuration_sample;

import com.workato.agent.extension.test.DeployedAgentLauncher;
import com.workato.agent.extension.test.DeployedAgentLauncher.RunningAgent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link ConfigurationSampleExtension} inside the real on-prem agent via
 * {@link DeployedAgentLauncher}. Manual-only — see
 * {@code extension-api/README-extension-test.md}, section
 * "Running against the real agent": each start() mutates process-global state
 * in the agent, so only one @Test method may run per JVM invocation.
 * Run one method at a time from the IDE.
 */
@Disabled("Manual-only — needs deployed+activated agent at AGENT_DIST; run one method per JVM.")
class ConfigurationSampleExtensionDeployedTest {

    private static final Path AGENT_DIST = Paths.get("/Users/krocodl/Apps/OPA-Preview-US-34");

    /**
     * Local-HTTP smoke test — verifies the extension answers through the
     * agent dispatcher. The deployed test cannot drive the cloud-managed
     * lifecycle callbacks (that requires a cloud push from the monolith), so
     * the assertions focus on what works in a bare agent process: static
     * {@code @Value} resolution and the {@code /info} response shape.
     */
    @Test
    void infoAnswersOverLocalHttp() throws Exception {
        try (RunningAgent agent = DeployedAgentLauncher.create("configuration_sample", AGENT_DIST)
                .controllerClass(ConfigurationSampleExtension.class)
                .property("environment", "preview")
                .openLocalAccess()
                .start()) {

            HttpResponse<String> res = agent.httpGet(ConfigurationSampleExtension::getInfo, 42L);

            assertEquals(200, res.statusCode(), "body=" + res.body());
            assertTrue(res.body().contains("\"environment\":\"preview\""),
                    "static per-adapter 'environment' not surfaced: " + res.body());
            assertTrue(res.body().contains("\"connectionVersion\""),
                    "connectionVersion missing: " + res.body());
            assertTrue(res.body().contains("\"updatedAt\""),
                    "updatedAt missing: " + res.body());
        }
    }

    @Test
    void connectsToWorkatoGateway() throws Exception {
        try (RunningAgent agent = DeployedAgentLauncher.create("configuration_sample", AGENT_DIST)
                .controllerClass(ConfigurationSampleExtension.class)
                .property("environment", "preview")
                // No .openLocalAccess(...) → full gateway mode.
                .start()) {

            String line = agent.awaitGatewayConnected(Duration.ofSeconds(60));
            assertNotNull(line, "awaitGatewayConnected returned null");
            assertTrue(line.contains("Connected to gateway"),
                    "unexpected gateway marker line: " + line);
        }
    }
}
