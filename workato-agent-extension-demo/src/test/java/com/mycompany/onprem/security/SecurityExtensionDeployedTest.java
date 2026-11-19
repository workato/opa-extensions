package com.mycompany.onprem.security;

import com.workato.agent.extension.test.DeployedAgentLauncher;
import com.workato.agent.extension.test.DeployedAgentLauncher.RunningAgent;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link SecurityExtension} inside the real on-prem agent via
 * {@link DeployedAgentLauncher}. Manual-only — see
 * {@code extension-api/README-extension-test.md}, section
 * "Running against the real agent": each start() mutates process-global state
 * in the agent, so only one @Test method may run per JVM invocation.
 * Run one method at a time from the IDE.
 */
@Disabled("Manual-only — needs deployed+activated agent at AGENT_DIST; run one method per JVM.")
class SecurityExtensionDeployedTest {

    private static final Path AGENT_DIST = Paths.get("/Users/krocodl/Apps/OPA-Preview-US-34");
    private static final String SECRET = "integration-secret";
    private static final String PAYLOAD = "hello";

    private static String sha256Hex(String secret, String payload) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(secret.getBytes(StandardCharsets.UTF_8));
        return Hex.encodeHexString(md.digest(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void computesDigestOverLocalHttp() throws Exception {
        try (RunningAgent agent = DeployedAgentLauncher.create("security", AGENT_DIST)
                .controllerClass(SecurityExtension.class)
                .property("signing-secret", SECRET)
                .openLocalAccess()
                .start()) {

            HttpResponse<String> res = agent.httpPost(
                    SecurityExtension::computeDigest,
                    Map.of("payload", PAYLOAD));

            assertEquals(200, res.statusCode(), "body=" + res.body());
            String expectedSig = sha256Hex(SECRET, PAYLOAD);
            assertTrue(res.body().contains("\"signature\"") && res.body().contains(expectedSig),
                    "expected signature " + expectedSig + " in body, got: " + res.body());
        }
    }

    @Test
    void connectsToWorkatoGateway() throws Exception {
        try (RunningAgent agent = DeployedAgentLauncher.create("security", AGENT_DIST)
                .controllerClass(SecurityExtension.class)
                .property("signing-secret", SECRET)
                // No .openLocalAccess(...) → full gateway mode.
                .start()) {

            String line = agent.awaitGatewayConnected(Duration.ofSeconds(60));
            assertNotNull(line, "awaitGatewayConnected returned null");
            assertTrue(line.contains("Connected to gateway"),
                    "unexpected gateway marker line: " + line);
        }
    }
}
