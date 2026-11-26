/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentWebhookV1.RetryPolicy;
import com.workato.agent.extension.api.AgentWebhookV1.WebhookDeliveryException;
import com.workato.agent.extension.api.AgentWebhookV1.WebhookResponse;
import com.workato.agent.extension.test.FakeAgentWebhookV1.CallKind;
import com.workato.agent.extension.test.FakeAgentWebhookV1.SentCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioural tests for the fake webhook implementation. These are the executable guarantee that
 * the fake honours {@link RetryPolicy} the same way the agent-core production impl does — if these
 * fail, extensions that pass against the fake will drift from how the agent actually behaves.
 */
class FakeAgentWebhookV1Test {

    private static final String URL = "https://recipe/webhook";
    private static final String OTHER = "https://other/webhook";

    FakeAgentWebhookV1 api;

    private static WebhookResponse canned(int status, String body) {
        return new WebhookResponse(status, body, false, Map.of(), 1, Duration.ZERO);
    }

    @BeforeEach
    void setUp() {
        api = new FakeAgentWebhookV1();
    }

    @Nested
    class DefaultBehaviour {

        @Test
        void sendWithoutStubReturnsCanned200AndRecordsOneAttempt() {
            WebhookResponse r = api.send(URL, Map.of("x", 1));

            assertEquals(200, r.statusCode());
            assertEquals("{}", r.body());
            assertEquals(1, r.attemptCount());
            assertEquals(1, api.allCalls().size());
            SentCall c = api.lastCall();
            assertEquals(CallKind.SEND, c.kind());
            assertEquals(URL, c.url());
            assertEquals(Map.of("x", 1), c.body());
        }

        @Test
        void wakeupWithoutStubReturnsCanned200AndRecordsOneAttempt() {
            WebhookResponse r = api.wakeup(URL);

            assertTrue(r.isSuccessful());
            assertEquals(1, api.allCalls().size());
            SentCall c = api.lastCall();
            assertEquals(CallKind.WAKEUP, c.kind());
            assertNull(c.body());
        }

        @Test
        void lastCallThrowsWhenEmpty() {
            assertThrows(IllegalStateException.class, () -> api.lastCall());
        }

        @Test
        void defaultRetryPolicyIsDEFAULTS() {
            assertEquals(RetryPolicy.DEFAULTS, api.getDefaultRetryPolicy());
        }

        @Test
        void setDefaultRetryPolicyIsReflectedBySubsequentSends() {
            RetryPolicy tight = RetryPolicy.DEFAULTS.withAttempts(2);
            api.setDefaultRetryPolicy(tight);
            api.whenSent(URL).thenReturn(503, "oops");

            assertThrows(WebhookDeliveryException.class, () -> api.send(URL, "x"));
            assertEquals(2, api.callsFor(URL).size(), "effective policy should limit to 2 attempts");
        }

        @Test
        void setDefaultRejectsNull() {
            assertThrows(IllegalArgumentException.class, () -> api.setDefaultRetryPolicy(null));
        }
    }

    @Nested
    class CallLog {

        @Test
        void allCallsIsASnapshotNotAView() {
            api.send(URL, "x");
            List<SentCall> snapshot = api.allCalls();
            api.send(URL, "y");

            assertEquals(1, snapshot.size(), "snapshot must not reflect later calls");
            assertEquals(2, api.allCalls().size());
        }

        @Test
        void callsForFiltersByExactUrl() {
            api.send(URL, "a");
            api.send(OTHER, "b");
            api.send(URL, "c");

            assertEquals(2, api.callsFor(URL).size());
            assertEquals(1, api.callsFor(OTHER).size());
        }

        @Test
        void callsOfKindFilters() {
            api.send(URL, "a");
            api.wakeup(URL);
            api.wakeup(OTHER);

            assertEquals(1, api.callsOfKind(CallKind.SEND).size());
            assertEquals(2, api.callsOfKind(CallKind.WAKEUP).size());
        }

        @Test
        void resetClearsLogAndStubs() {
            // 404 is non-retryable under DEFAULTS → one attempt, test stays focused on reset()
            api.whenSent(URL).thenReturn(404, "nope");
            api.send(URL, "a");

            api.reset();

            assertTrue(api.allCalls().isEmpty());
            // Reset also clears stubs — next call hits the canned 200 default.
            assertEquals(200, api.send(URL, "a").statusCode());
        }

        @Test
        void resetDoesNotTouchDefaultPolicy() {
            api.setDefaultRetryPolicy(RetryPolicy.DEFAULTS.withAttempts(2));
            api.reset();
            assertEquals(2, api.getDefaultRetryPolicy().maxAttempts());
        }

        @Test
        void capturedAttemptCountMatchesIteration() {
            api.whenSent(URL).thenReturnSequence(
                    canned(503, "retry"),
                    canned(503, "retry"),
                    canned(200, "ok"));

            api.send(URL, "x");

            List<SentCall> calls = api.callsFor(URL);
            assertEquals(3, calls.size());
            assertEquals(1, calls.get(0).attemptNumber());
            assertEquals(2, calls.get(1).attemptNumber());
            assertEquals(3, calls.get(2).attemptNumber());
            // The response the extension observes carries the final attempt count.
            assertEquals(3, calls.get(2).response().attemptCount());
        }
    }

    @Nested
    class RetryObservesPolicy {

        @Test
        void retryableStatusRetriesUntilExhaustionThenThrows() {
            api.whenSent(URL).thenReturn(503, "down");

            WebhookDeliveryException ex = assertThrows(WebhookDeliveryException.class,
                    () -> api.send(URL, "x"));

            assertEquals(5, api.callsFor(URL).size(), "DEFAULTS has maxAttempts=5");
            assertEquals(URL, ex.getUrl());
            assertEquals(5, ex.getAttemptCount());
            assertNotNull(ex.getLastResponse(), "exhausted-with-response path should carry the last response");
            assertEquals(503, ex.getLastResponse().statusCode());
        }

        @Test
        void nonRetryableStatusReturnsImmediately() {
            api.whenSent(URL).thenReturn(400, "bad request");

            WebhookResponse r = api.send(URL, "x");

            assertEquals(400, r.statusCode());
            assertEquals(1, api.callsFor(URL).size(), "4xx should not be retried under DEFAULTS");
        }

        @Test
        void successStopsRetrying() {
            api.whenSent(URL).thenReturnSequence(
                    canned(503, "retry"),
                    canned(200, "ok"));

            WebhookResponse r = api.send(URL, "x");

            assertEquals(200, r.statusCode());
            assertEquals(2, api.callsFor(URL).size());
            assertEquals(2, r.attemptCount());
        }

        @Test
        void retryableExceptionRetriesUntilExhaustion() {
            api.whenSent(URL).thenFail(new SocketTimeoutException("ssh"));

            WebhookDeliveryException ex = assertThrows(WebhookDeliveryException.class,
                    () -> api.send(URL, "x"));

            assertEquals(5, api.callsFor(URL).size());
            // Final failure was an exception; no response payload.
            assertNull(ex.getLastResponse());
        }

        @Test
        void nonRetryableExceptionThrowsImmediately() {
            RetryPolicy strict = RetryPolicy.DEFAULTS.withAttempts(5).withJitter(0.0);
            // Reject all exceptions as non-retryable.
            RetryPolicy nonRetryable = new RetryPolicy(
                    strict.maxAttempts(), strict.initialDelay(), strict.multiplier(),
                    strict.maxDelay(), strict.jitterFactor(), strict.requestTimeout(),
                    strict.retryableStatusCode(),
                    e -> false);
            api.setDefaultRetryPolicy(nonRetryable);
            api.whenSent(URL).thenFail(new IOException("boom"));

            WebhookDeliveryException ex = assertThrows(WebhookDeliveryException.class,
                    () -> api.send(URL, "x"));

            assertEquals(1, api.callsFor(URL).size(), "non-retryable exception should not loop");
            assertEquals("boom", ex.getCause().getMessage());
        }

        @Test
        void thenFailAllIsRetryedUnderDefaults() {
            api.whenSent(URL).thenFailAll();
            assertThrows(WebhookDeliveryException.class, () -> api.send(URL, "x"));
            assertEquals(5, api.callsFor(URL).size());
        }

        @Test
        void perCallPolicyOverridesDefault() {
            api.whenSent(URL).thenReturn(503, "down");
            RetryPolicy twoTries = RetryPolicy.DEFAULTS.withAttempts(2);

            assertThrows(WebhookDeliveryException.class, () -> api.send(URL, "x", twoTries));

            assertEquals(2, api.callsFor(URL).size());
        }
    }

    @Nested
    class StubResolution {

        @Test
        void mostRecentlyRegisteredStubWins() {
            api.whenSent(URL).thenReturn(500, "first");
            api.whenSent(URL).thenReturn(200, "second");

            assertEquals(200, api.send(URL, "x").statusCode());
        }

        @Test
        void whenCalledMatchesBothKinds() {
            api.whenCalled(URL).thenReturn(201, "any");

            assertEquals(201, api.send(URL, "x").statusCode());
            assertEquals(201, api.wakeup(URL).statusCode());
        }

        @Test
        void whenSentDoesNotMatchWakeup() {
            api.whenSent(URL).thenReturn(201, "SEND only");

            // wakeup falls through to the default 200 "{}"
            WebhookResponse wakeupResp = api.wakeup(URL);
            assertEquals(200, wakeupResp.statusCode());
            assertEquals("{}", wakeupResp.body());

            // send does match
            assertEquals(201, api.send(URL, "x").statusCode());
        }

        @Test
        void whenWokenUpDoesNotMatchSend() {
            api.whenWokenUp(URL).thenReturn(202, "WAKEUP only");

            assertEquals(200, api.send(URL, "x").statusCode());
            assertEquals(202, api.wakeup(URL).statusCode());
        }

        @Test
        void whenCalledMatchingAcceptsPredicate() {
            api.whenCalledMatching(url -> url.startsWith("https://recipe/")).thenReturn(418, "teapot");

            assertEquals(418, api.send(URL, "x").statusCode());
            // OTHER does not match
            assertEquals(200, api.send(OTHER, "x").statusCode());
        }

        @Test
        void stubBuilderRejectsNullCause() {
            assertThrows(IllegalArgumentException.class, () -> api.whenCalled(URL).thenFail(null));
        }

        @Test
        void stubBuilderRejectsNullResponse() {
            assertThrows(IllegalArgumentException.class, () -> api.whenCalled(URL).thenReturn(null));
        }

        @Test
        void sequenceRejectsEmptyAndNullEntries() {
            assertThrows(IllegalArgumentException.class, () -> api.whenCalled(URL).thenReturnSequence());
            assertThrows(IllegalArgumentException.class,
                    () -> api.whenCalled(URL).thenReturnSequence((WebhookResponse) null));
        }

        @Test
        void sequenceReusesLastEntryWhenExhausted() {
            api.whenSent(URL).thenReturnSequence(
                    canned(503, "retry"),
                    canned(503, "retry"));
            // Both responses retryable, DEFAULTS has 5 attempts → sequence exhausts after 2, then
            // reuses the last entry (another 503) three more times → exhaustion.
            assertThrows(WebhookDeliveryException.class, () -> api.send(URL, "x"));
            assertEquals(5, api.callsFor(URL).size());
        }
    }

    @Nested
    class ArgumentValidation {

        @Test
        void sendRejectsNullUrl() {
            assertThrows(IllegalArgumentException.class, () -> api.send(null, "x"));
        }

        @Test
        void wakeupRejectsNullUrl() {
            assertThrows(IllegalArgumentException.class, () -> api.wakeup(null));
        }

        @Test
        void sendRejectsNullPolicyOverload() {
            assertThrows(IllegalArgumentException.class, () -> api.send(URL, "x", (RetryPolicy) null));
        }

        @Test
        void wakeupRejectsNullPolicyOverload() {
            assertThrows(IllegalArgumentException.class, () -> api.wakeup(URL, (RetryPolicy) null));
        }

        @Test
        void stubBuilderExactMatchRejectsNullUrl() {
            assertThrows(IllegalArgumentException.class, () -> api.whenCalled(null));
        }
    }

    @Nested
    class HeadersPropagate {

        @Test
        void customHeadersAppearInCallLog() {
            api.send(URL, "x", Map.of("X-Request-ID", List.of("req-1")));
            assertEquals(Map.of("X-Request-ID", List.of("req-1")), api.lastCall().headers());
        }

        @Test
        void multiValueHeadersPreserveOrder() {
            api.send(URL, "x", Map.of("Accept", List.of("application/json", "text/plain")));
            assertEquals(List.of("application/json", "text/plain"),
                    api.lastCall().headers().get("Accept"));
        }

        @Test
        void defaultHeadersAreEmptyMap() {
            api.send(URL, "x");
            assertEquals(Map.of(), api.lastCall().headers());
        }

        @Test
        void nullHeadersBecomeEmpty() {
            api.send(URL, "x", (Map<String, List<String>>) null);
            assertEquals(Map.of(), api.lastCall().headers());
        }

        @Test
        void nullBodyIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> api.send(URL, null));
        }
    }
}
