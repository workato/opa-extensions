/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentWebhookV1.RetryPolicy;
import com.workato.agent.extension.api.AgentWebhookV1.WebhookDeliveryException;
import com.workato.agent.extension.api.AgentWebhookV1.WebhookResponse;
import com.workato.agent.extension.test.FakeAgentWebhookV1.CallKind;
import com.workato.agent.extension.test.testext.WebhookInvokingController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-stack integration test: a Spring-managed {@code @RestController} resolves the
 * {@link com.workato.agent.extension.api.AgentWebhookV1} through autowiring and delivery
 * attempts land in the harness's {@link FakeAgentWebhookV1}. Verifies the test-time wiring the
 * harness provides.
 */
class WebhookInvokingControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("hook-test")
            .controller(WebhookInvokingController.class);

    @BeforeEach
    void resetWebhookState() {
        // Class-level harness → the same FakeAgentWebhookV1 lives across all tests. Clean it
        // before each case so stubs and call log from the previous test do not leak.
        agent.webhook().reset();
        agent.webhook().setDefaultRetryPolicy(RetryPolicy.DEFAULTS);
    }

    @Test
    void sendRoundTripsPayloadThroughFakeApi() throws Exception {
        agent.webhook().whenSent("https://recipe/webhook").thenReturn(200, "{\"ok\":true}");

        MvcResult result = perform(agent.mockMvc(), agent.request(WebhookInvokingController::fire)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"x\":1}"));

        assertEquals(200, status(result));
        assertEquals(1, agent.webhook().callsFor("https://recipe/webhook").size());
        FakeAgentWebhookV1.SentCall captured = agent.webhook().lastCall();
        assertEquals(CallKind.SEND, captured.kind());
        assertEquals(Map.of("x", 1), captured.body());
    }

    @Test
    void customHeadersPropagateThroughSendOverload() throws Exception {
        MvcResult result = perform(agent.mockMvc(), agent.request(WebhookInvokingController::fireWithHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"a\":2}"));

        assertEquals(200, status(result));
        assertEquals(Map.of("X-Request-ID", "fixed-id-for-test"),
                agent.webhook().lastCall().headers());
    }

    @Test
    void wakeupLandsAsWakeupCall() throws Exception {
        MvcResult result = perform(agent.mockMvc(), agent.request(WebhookInvokingController::wake));

        assertEquals(200, status(result));
        assertEquals(1, agent.webhook().callsOfKind(CallKind.WAKEUP).size());
        assertEquals("https://recipe/trigger", agent.webhook().lastCall().url());
        assertNull(agent.webhook().lastCall().body(), "wakeup carries no body");
    }

    @Test
    void retriesVisibleInCallLogBeforeExhaustion() {
        agent.webhook().setDefaultRetryPolicy(RetryPolicy.DEFAULTS.withAttempts(3));
        agent.webhook().whenSent("https://recipe/webhook").thenReturn(503, "down");

        // MockMvc rethrows uncaught controller exceptions wrapped in jakarta.servlet.ServletException;
        // asserting on the cause chain is enough to pin that delivery exhaustion propagated.
        Exception thrown = assertThrows(Exception.class,
                () -> perform(agent.mockMvc(), agent.request(WebhookInvokingController::fire)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":1}")));

        Throwable root = thrown;
        while (root != null && !(root instanceof WebhookDeliveryException)) {
            root = root.getCause();
        }
        assertNotNull(root, "Expected a WebhookDeliveryException in the cause chain, got " + thrown);
        WebhookDeliveryException delivery = (WebhookDeliveryException) root;
        assertEquals("https://recipe/webhook", delivery.getUrl());
        assertEquals(3, delivery.getAttemptCount());

        assertEquals(3, agent.webhook().callsFor("https://recipe/webhook").size(),
                "all 3 attempts should land in the call log before exhaustion");
    }

    @Test
    void responseAsClassWorksOverTheHarness() throws Exception {
        agent.webhook()
                .whenSent("https://recipe/webhook")
                .thenReturn(new WebhookResponse(200, "{\"id\":\"abc\",\"code\":42}",
                        false, Map.of(), 1, Duration.ZERO));

        MvcResult result = perform(agent.mockMvc(), agent.request(WebhookInvokingController::fireAndParse)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"x\":1}"));

        assertEquals(200, status(result));
        Map<String, Object> body = jsonMap(result);
        assertEquals("abc", body.get("ackId"));
        assertEquals(42, body.get("ackCode"));
        // X-Request-ID is set by the controller to a fresh UUID each call — just assert presence.
        assertTrue(agent.webhook().lastCall().headers().containsKey("X-Request-ID"));
    }

    @Test
    void webhookExposedViaBothAccessors() {
        assertSame(agent.webhook(),
                agent.agentExtension().webhook(),
                "both the typed accessor and the generic path through AgentExtensionV1 return the same fake");
    }
}
