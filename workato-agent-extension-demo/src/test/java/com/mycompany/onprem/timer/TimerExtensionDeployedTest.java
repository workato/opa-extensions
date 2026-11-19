package com.mycompany.onprem.timer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.onprem.timer.TimerExtension.FireNowRequest;
import com.mycompany.onprem.timer.TimerExtension.FireNowResponse;
import com.mycompany.onprem.timer.TimerExtension.SubscribeRequest;
import com.mycompany.onprem.timer.TimerExtension.SubscribeResponse;
import com.sun.net.httpserver.HttpServer;
import com.workato.agent.extension.test.DeployedAgentLauncher;
import com.workato.agent.extension.test.DeployedAgentLauncher.RunningAgent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@link TimerExtension} inside the real on-prem agent via
 * {@link DeployedAgentLauncher}. Manual-only — see
 * {@code extension-api/README-extension-test.md}, section
 * "Running against the real agent": each start() mutates process-global state
 * in the agent, so only one @Test method may run per JVM invocation.
 * Run one method at a time from the IDE.
 */
@Disabled("Manual-only — needs deployed+activated agent at AGENT_DIST; run one method per JVM.")
class TimerExtensionDeployedTest {

    private static final Path AGENT_DIST = Paths.get("/Users/krocodl/Apps/OPA-Preview-US-34");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void firesEventToLocalSinkOverLocalHttp() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedEventId = new AtomicReference<>();

        HttpServer sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sink.createContext("/", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            capturedEventId.set(exchange.getRequestHeaders().getFirst("X-Workato-Event-Id"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        sink.start();
        String sinkUrl = "http://127.0.0.1:" + sink.getAddress().getPort() + "/";

        try (RunningAgent agent = DeployedAgentLauncher.create("timer", AGENT_DIST)
                .controllerClass(TimerExtension.class)
                // 60s period keeps the scheduler from ticking during this test; we drive
                // delivery through fire_now instead to make the assertion deterministic.
                .property("min-period-seconds", "60")
                .openLocalAccess()
                .start()) {

            HttpResponse<String> subRes = agent.httpPost(
                    TimerExtension::subscribe,
                    new SubscribeRequest(sinkUrl, "recipe-1", "conn-1", "hello", 60));
            assertEquals(200, subRes.statusCode(), "subscribe body=" + subRes.body());
            String id = MAPPER.readValue(subRes.body(), SubscribeResponse.class).id();
            assertNotNull(id, "no subscription id in subscribe response");

            HttpResponse<String> fireRes = agent.httpPost(
                    TimerExtension::fireNow, new FireNowRequest(id));
            assertEquals(200, fireRes.statusCode(), "fire_now body=" + fireRes.body());
            FireNowResponse fr = MAPPER.readValue(fireRes.body(), FireNowResponse.class);
            assertTrue(fr.delivered(), "extension reported delivery failure: " + fireRes.body());
            assertEquals(200, fr.statusCode());

            assertNotNull(capturedBody.get(), "sink did not receive the webhook event");
            assertTrue(capturedBody.get().contains("\"message\":\"hello\""), capturedBody.get());
            assertTrue(capturedBody.get().contains("\"tick\":1"), capturedBody.get());
            assertTrue(capturedBody.get().contains("\"subscription_id\":\"" + id + "\""), capturedBody.get());
            assertNotNull(capturedEventId.get(), "X-Workato-Event-Id header missing on delivery");
        } finally {
            sink.stop(0);
        }
    }

    @Test
    void connectsToWorkatoGateway() throws Exception {
        try (RunningAgent agent = DeployedAgentLauncher.create("timer", AGENT_DIST)
                .controllerClass(TimerExtension.class)
                .property("min-period-seconds", "10")
                // No .openLocalAccess(...) → full gateway mode.
                .start()) {

            String line = agent.awaitGatewayConnected(Duration.ofSeconds(60));
            assertNotNull(line, "awaitGatewayConnected returned null");
            assertTrue(line.contains("Connected to gateway"),
                    "unexpected gateway marker line: " + line);
        }
    }

}
