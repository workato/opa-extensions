package com.mycompany.onprem.ntlm;

import com.workato.agent.extension.test.DeployedAgentLauncher;
import com.workato.agent.extension.test.DeployedAgentLauncher.RunningAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link NtlmExtension} inside the real on-prem agent via
 * {@link DeployedAgentLauncher}. Manual-only — see
 * {@code extension-api/README-extension-test.md}, section
 * "Running against the real agent": each start() mutates process-global state
 * in the agent, so only one @Test method may run per JVM invocation.
 * Run one method at a time from the IDE.
 */
@Disabled("Manual-only — needs deployed+activated agent at AGENT_DIST; run one method per JVM.")
class NtlmExtensionDeployedTest {

    private static final Path AGENT_DIST = Paths.get("/Users/krocodl/Apps/OPA-34-test");

    private LocalUpstreamServer upstream;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = LocalUpstreamServer.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    void forwardsUpstreamBodyOverLocalHttp() throws Exception {
        try (RunningAgent agent = DeployedAgentLauncher.create("ntlm", AGENT_DIST)
                .controllerClass(NtlmExtension.class)
                .property("connect-timeout-ms", "2000")
                .property("read-timeout-ms", "5000")
                .property("ntlm.allowed-hosts", "127.0.0.1")
                .openLocalAccess()
                .start()) {

            HttpRequest req = agent.requestFor(NtlmExtension::request)
                    .header("X-Workato-NTLM-auth", "user:pass@WORKGROUP/WS01")
                    .header("X-Workato-NTLM-url", upstream.urlFor("/ok"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> res = agent.send(req);

            assertEquals(200, res.statusCode(), "body=" + res.body());
            assertEquals(LocalUpstreamServer.OK_BODY, res.body());
            assertEquals(1, upstream.okHits.get(), "upstream must be contacted exactly once");
        }
    }

    @Test
    void connectsToWorkatoGateway() throws Exception {
        try (RunningAgent agent = DeployedAgentLauncher.create("ntlm", AGENT_DIST)
                .controllerClass(NtlmExtension.class)
                .property("connect-timeout-ms", "2000")
                .property("read-timeout-ms", "5000")
                // No .openLocalAccess(...) → full gateway mode.
                .start()) {

            String line = agent.awaitGatewayConnected(Duration.ofSeconds(60));
            assertNotNull(line, "awaitGatewayConnected returned null");
            assertTrue(line.contains("Connected to gateway"),
                    "unexpected gateway marker line: " + line);
        }
    }
}
