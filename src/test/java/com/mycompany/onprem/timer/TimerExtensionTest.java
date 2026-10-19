package com.mycompany.onprem.timer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompany.onprem.timer.TimerExtension.ErrorResponse;
import com.mycompany.onprem.timer.TimerExtension.FireNowResponse;
import com.mycompany.onprem.timer.TimerExtension.SubscribeResponse;
import com.mycompany.onprem.timer.TimerExtension.TimerEvent;
import com.workato.agent.extension.api.AgentWebhookApiV1.RetryPolicy;
import com.workato.agent.extension.test.AgentExtensionHarness;
import com.workato.agent.extension.test.FakeAgentWebhookApiV1.CallKind;
import com.workato.agent.extension.test.FakeAgentWebhookApiV1.SentCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class TimerExtensionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("timer")
            .yaml("extensions/timer.yml");

    private static String subscribe(AgentExtensionHarness h,
                                    String url, int periodSeconds, String message) throws Exception {
        String json = MAPPER.writeValueAsString(Map.of(
                "workato_webhook_url", url,
                "recipe_id", "recipe-xyz",
                "connection_id", "conn-1",
                "message", message,
                "period_seconds", periodSeconds));
        MvcResult result = postJson(h, "/webhook/subscribe", json);
        assertEquals(200, result.getResponse().getStatus(),
                "subscribe body=" + result.getResponse().getContentAsString());
        return MAPPER.readValue(result.getResponse().getContentAsString(), SubscribeResponse.class).id();
    }

    private static MvcResult postJson(AgentExtensionHarness h, String path, String json) throws Exception {
        return h.mockMvc().perform(post(h.urlFor(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private static void awaitAtLeast(AgentExtensionHarness h, int n, Duration deadline) throws Exception {
        Instant end = Instant.now().plus(deadline);
        while (Instant.now().isBefore(end)) {
            if (h.webhookApi().allCalls().size() >= n) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("expected at least " + n + " webhook calls within " + deadline
                + ", got " + h.webhookApi().allCalls().size());
    }

    @BeforeEach
    void resetWebhookState() {
        agent.webhookApi().reset();
    }

    @AfterEach
    void cancelSubscriptions() {
        // Clear every subscription registered during the test — this stops the
        // scheduler ticking into the next test's fake-webhook assertions. Reaches
        // into the bean because we deliberately don't expose the state over HTTP.
        agent.webApplicationContext().getBean(TimerExtension.class).cancelAllSubscriptions();
    }

    @Test
    void subscribeReturnsIdUuid() throws Exception {
        agent.webhookApi().whenSent("https://recipe/wh").thenReturn(200, "{}");
        String id = subscribe(agent, "https://recipe/wh", 5, "hi");
        UUID parsed = assertDoesNotThrow(() -> UUID.fromString(id), "not a UUID: " + id);
        assertEquals(4, parsed.version(), "expected a UUIDv4");
    }

    @Test
    void fireNowPostsExpectedPayload() throws Exception {
        agent.webhookApi().whenSent("https://recipe/wh").thenReturn(200, "{}");
        String id = subscribe(agent, "https://recipe/wh", 5, "hi");

        MvcResult fire = postJson(agent, "/webhook/fire_now",
                MAPPER.writeValueAsString(Map.of("subscription_id", id)));
        assertEquals(200, fire.getResponse().getStatus());

        FireNowResponse fr = MAPPER.readValue(fire.getResponse().getContentAsString(), FireNowResponse.class);
        assertTrue(fr.delivered());
        assertEquals(200, fr.statusCode());

        SentCall call = agent.webhookApi().lastCall();
        assertEquals(CallKind.SEND, call.kind());
        assertEquals("https://recipe/wh", call.url());
        assertNotNull(call.headers().get("X-Workato-Event-Id"));

        TimerEvent event = MAPPER.convertValue(call.body(), TimerEvent.class);
        assertEquals("hi", event.message());
        assertEquals(1L, event.tick());
        assertEquals(id, event.subscriptionId());
        assertEquals(5, event.periodSeconds());
        assertTrue(event.generatedAt().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"),
                "not ISO-8601: " + event.generatedAt());
    }

    @Test
    void fireNowUnknownIdReturns404() throws Exception {
        MvcResult result = postJson(agent, "/webhook/fire_now",
                MAPPER.writeValueAsString(Map.of("subscription_id", "does-not-exist")));
        assertEquals(404, result.getResponse().getStatus());
        assertTrue(agent.webhookApi().allCalls().isEmpty());
    }

    @Test
    void subscribeWithBlankMessageReturns400() throws Exception {
        String json = MAPPER.writeValueAsString(Map.of(
                "workato_webhook_url", "https://recipe/wh",
                "recipe_id", "r", "connection_id", "c",
                "message", "   ", "period_seconds", 5));
        MvcResult result = postJson(agent, "/webhook/subscribe", json);
        assertEquals(400, result.getResponse().getStatus());
        ErrorResponse err = MAPPER.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        assertTrue(err.error().contains("message must not be blank"),
                "unexpected error: " + err.error());
    }

    @Test
    void subscribeWithBlankUrlReturns400() throws Exception {
        String json = MAPPER.writeValueAsString(Map.of(
                "workato_webhook_url", "",
                "recipe_id", "r", "connection_id", "c",
                "message", "hi", "period_seconds", 5));
        MvcResult result = postJson(agent, "/webhook/subscribe", json);
        assertEquals(400, result.getResponse().getStatus());
        ErrorResponse err = MAPPER.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
        assertTrue(err.error().contains("workato_webhook_url"),
                "unexpected error: " + err.error());
    }

    @Test
    void subscribeBelowMinReturns400() throws Exception {
        AgentExtensionHarness strict = AgentExtensionHarness.extension("timer-strict")
                .controller(TimerExtension.class)
                .property("min-period-seconds", "10")
                .start();
        try {
            String json = MAPPER.writeValueAsString(Map.of(
                    "workato_webhook_url", "https://recipe/wh",
                    "recipe_id", "r", "connection_id", "c",
                    "message", "hi", "period_seconds", 1));
            MvcResult result = postJson(strict, "/webhook/subscribe", json);
            assertEquals(400, result.getResponse().getStatus());
            ErrorResponse err = MAPPER.readValue(result.getResponse().getContentAsString(), ErrorResponse.class);
            assertTrue(err.error().contains("must be >="), "unexpected error: " + err.error());
        } finally {
            strict.stop();
        }
    }

    @Test
    void unsubscribeCancelsScheduledTask() throws Exception {
        agent.webhookApi().whenSent("https://recipe/wh").thenReturn(200, "{}");
        String id = subscribe(agent, "https://recipe/wh", 1, "tick");

        awaitAtLeast(agent, 1, Duration.ofSeconds(3));
        postJson(agent, "/webhook/unsubscribe",
                MAPPER.writeValueAsString(Map.of("recipe_id", id, "connection_id", "conn-1")));

        int n = agent.webhookApi().allCalls().size();
        // One period + a cushion — long enough for a stray tick to show up if cancel didn't take.
        Thread.sleep(1500);
        assertEquals(n, agent.webhookApi().allCalls().size(),
                "scheduler kept firing after unsubscribe");
    }

    @Test
    void contextShutdownStopsScheduler() throws Exception {
        AgentExtensionHarness alt = AgentExtensionHarness.extension("timer-alt")
                .controller(TimerExtension.class)
                .property("min-period-seconds", "1")
                .start();
        try {
            var fake = alt.webhookApi();
            fake.whenSent("https://recipe/wh").thenReturn(200, "{}");
            subscribe(alt, "https://recipe/wh", 1, "tick");
            awaitAtLeast(alt, 1, Duration.ofSeconds(3));
            int n = fake.allCalls().size();
            alt.stop();
            Thread.sleep(1500);
            assertEquals(n, fake.allCalls().size(),
                    "scheduler kept firing after context shutdown");
        } finally {
            // Idempotent — stop() above is the happy path, this covers early failures.
            try {
                alt.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void multipleSubscriptionsFireIndependently() throws Exception {
        agent.webhookApi().whenSent("https://a/wh").thenReturn(200, "{}");
        agent.webhookApi().whenSent("https://b/wh").thenReturn(200, "{}");
        String idA = subscribe(agent, "https://a/wh", 5, "A");
        String idB = subscribe(agent, "https://b/wh", 5, "B");

        postJson(agent, "/webhook/fire_now", MAPPER.writeValueAsString(Map.of("subscription_id", idA)));
        postJson(agent, "/webhook/fire_now", MAPPER.writeValueAsString(Map.of("subscription_id", idB)));

        assertEquals(1, agent.webhookApi().callsFor("https://a/wh").size());
        assertEquals(1, agent.webhookApi().callsFor("https://b/wh").size());

        TimerEvent ea = MAPPER.convertValue(
                agent.webhookApi().callsFor("https://a/wh").get(0).body(), TimerEvent.class);
        TimerEvent eb = MAPPER.convertValue(
                agent.webhookApi().callsFor("https://b/wh").get(0).body(), TimerEvent.class);
        assertEquals(idA, ea.subscriptionId());
        assertEquals(idB, eb.subscriptionId());
        assertEquals("A", ea.message());
        assertEquals("B", eb.message());
    }

    @Test
    void swallowsDeliveryFailuresInScheduledTick() throws Exception {
        // Cap retries at 1 so each tick = exactly one captured attempt — then
        // waiting for ≥2 attempts proves the scheduler thread survived the
        // first tick's failure and kept firing. With the default (5 attempts)
        // the first tick alone would satisfy ≥2 and the test would pass even
        // if the scheduler died.
        agent.webhookApi().setDefaultRetryPolicy(RetryPolicy.DEFAULTS.withAttempts(1));
        agent.webhookApi().whenSent("https://recipe/wh").thenFailAll();
        subscribe(agent, "https://recipe/wh", 1, "tick");

        awaitAtLeast(agent, 2, Duration.ofSeconds(5));
    }
}
