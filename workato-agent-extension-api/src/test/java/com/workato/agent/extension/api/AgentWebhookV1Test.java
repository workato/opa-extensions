/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.api;

import com.workato.agent.extension.api.AgentWebhookV1.RetryPolicy;
import com.workato.agent.extension.api.AgentWebhookV1.WebhookDeliveryException;
import com.workato.agent.extension.api.AgentWebhookV1.WebhookResponse;
import com.workato.agent.extension.api.AgentWebhookV1.WebhookResponseException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the nested value types on {@link AgentWebhookV1} — the vocabulary customer
 * extensions depend on without ever touching the agent-core implementation.
 *
 * <p>These tests cover record validation, immutability guarantees, the {@link RetryPolicy}
 * default values and wither-style copies, {@link WebhookResponse#isSuccessful()} /
 * {@link WebhookResponse#as(Class, ObjectMapper)} happy and failure paths, and the shape of the
 * two unchecked exceptions. Behavioural testing of the full retry loop lives in the agent-core
 * impl's own test and in the fake / parity suite in {@code extension-test}.
 */
class AgentWebhookV1Test {

    @Nested
    class RetryPolicyDefaults {

        @Test
        void defaultsMatchDocumentedValues() {
            assertEquals(5, RetryPolicy.DEFAULTS.maxAttempts());
            assertEquals(Duration.ofSeconds(1), RetryPolicy.DEFAULTS.initialDelay());
            assertEquals(2.0, RetryPolicy.DEFAULTS.multiplier());
            assertEquals(Duration.ofSeconds(30), RetryPolicy.DEFAULTS.maxDelay());
            assertEquals(0.0, RetryPolicy.DEFAULTS.jitterFactor());
            assertEquals(Duration.ofSeconds(30), RetryPolicy.DEFAULTS.requestTimeout());
        }

        @Test
        void defaultRetryableStatusCodeAcceptsFiveHundredsAnd408And429() {
            assertTrue(RetryPolicy.DEFAULTS.retryableStatusCode().test(500));
            assertTrue(RetryPolicy.DEFAULTS.retryableStatusCode().test(503));
            assertTrue(RetryPolicy.DEFAULTS.retryableStatusCode().test(408));
            assertTrue(RetryPolicy.DEFAULTS.retryableStatusCode().test(429));
        }

        @Test
        void defaultRetryableStatusCodeRejectsSuccessAnd4xx() {
            assertFalse(RetryPolicy.DEFAULTS.retryableStatusCode().test(200));
            assertFalse(RetryPolicy.DEFAULTS.retryableStatusCode().test(201));
            assertFalse(RetryPolicy.DEFAULTS.retryableStatusCode().test(400));
            assertFalse(RetryPolicy.DEFAULTS.retryableStatusCode().test(404));
        }
    }

    @Nested
    class RetryPolicyValidation {

        @Test
        void rejectsZeroMaxAttempts() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RetryPolicy.DEFAULTS.withAttempts(0));
            assertTrue(ex.getMessage().contains("maxAttempts"));
        }

        @Test
        void rejectsNegativeMaxAttempts() {
            assertThrows(IllegalArgumentException.class, () -> RetryPolicy.DEFAULTS.withAttempts(-1));
        }

        @Test
        void rejectsNegativeInitialDelay() {
            assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(
                    5, Duration.ofSeconds(-1), 2.0, Duration.ofSeconds(30), 0.0,
                    Duration.ofSeconds(30),
                    RetryPolicy.DEFAULTS.retryableStatusCode(),
                    RetryPolicy.DEFAULTS.retryableException()));
        }

        @Test
        void rejectsNullInitialDelay() {
            assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(
                    5, null, 2.0, Duration.ofSeconds(30), 0.0, Duration.ofSeconds(30),
                    RetryPolicy.DEFAULTS.retryableStatusCode(),
                    RetryPolicy.DEFAULTS.retryableException()));
        }

        @Test
        void rejectsMultiplierBelowOne() {
            var ex = assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(
                    5, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(30), 0.0,
                    Duration.ofSeconds(30),
                    RetryPolicy.DEFAULTS.retryableStatusCode(),
                    RetryPolicy.DEFAULTS.retryableException()));
            assertTrue(ex.getMessage().contains("multiplier"));
        }

        @Test
        void rejectsJitterAboveOne() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> RetryPolicy.DEFAULTS.withJitter(1.5));
            assertTrue(ex.getMessage().contains("jitterFactor"));
        }

        @Test
        void rejectsNegativeJitter() {
            assertThrows(IllegalArgumentException.class,
                    () -> RetryPolicy.DEFAULTS.withJitter(-0.1));
        }

        @Test
        void rejectsZeroRequestTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> RetryPolicy.DEFAULTS.withTimeout(Duration.ZERO));
        }

        @Test
        void rejectsNegativeRequestTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> RetryPolicy.DEFAULTS.withTimeout(Duration.ofSeconds(-1)));
        }

        @Test
        void acceptsJitterAtBoundaries() {
            assertDoesNotThrow(() -> RetryPolicy.DEFAULTS.withJitter(0.0));
            assertDoesNotThrow(() -> RetryPolicy.DEFAULTS.withJitter(1.0));
        }

        @Test
        void acceptsMultiplierExactlyOne() {
            assertDoesNotThrow(() -> new RetryPolicy(
                    5, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(30), 0.0,
                    Duration.ofSeconds(30),
                    RetryPolicy.DEFAULTS.retryableStatusCode(),
                    RetryPolicy.DEFAULTS.retryableException()));
        }
    }

    @Nested
    class RetryPolicyWithers {

        @Test
        void withAttemptsReturnsNewInstanceWithOnlyThatFieldChanged() {
            RetryPolicy p = RetryPolicy.DEFAULTS.withAttempts(10);
            assertEquals(10, p.maxAttempts());
            assertEquals(RetryPolicy.DEFAULTS.initialDelay(), p.initialDelay());
            assertEquals(RetryPolicy.DEFAULTS.multiplier(), p.multiplier());
            assertEquals(RetryPolicy.DEFAULTS.maxDelay(), p.maxDelay());
            assertEquals(RetryPolicy.DEFAULTS.jitterFactor(), p.jitterFactor());
            assertEquals(RetryPolicy.DEFAULTS.requestTimeout(), p.requestTimeout());
            assertSame(RetryPolicy.DEFAULTS.retryableStatusCode(), p.retryableStatusCode());
            assertSame(RetryPolicy.DEFAULTS.retryableException(), p.retryableException());
        }

        @Test
        void withTimeoutReturnsNewInstance() {
            RetryPolicy p = RetryPolicy.DEFAULTS.withTimeout(Duration.ofSeconds(5));
            assertEquals(Duration.ofSeconds(5), p.requestTimeout());
            assertEquals(RetryPolicy.DEFAULTS.maxAttempts(), p.maxAttempts());
        }

        @Test
        void withJitterReturnsNewInstance() {
            RetryPolicy p = RetryPolicy.DEFAULTS.withJitter(0.3);
            assertEquals(0.3, p.jitterFactor());
            assertEquals(RetryPolicy.DEFAULTS.maxAttempts(), p.maxAttempts());
        }

        @Test
        void withersDoNotMutateDefaults() {
            RetryPolicy before = RetryPolicy.DEFAULTS;
            RetryPolicy.DEFAULTS.withAttempts(99);
            RetryPolicy.DEFAULTS.withJitter(0.9);
            RetryPolicy.DEFAULTS.withTimeout(Duration.ofHours(1));
            assertEquals(before, RetryPolicy.DEFAULTS);
        }
    }

    @Nested
    class WebhookResponseBasics {

        @Test
        void isSuccessfulAcceptsTwoHundreds() {
            assertTrue(new WebhookResponse(200, "", false, Map.of(), 1, Duration.ZERO).isSuccessful());
            assertTrue(new WebhookResponse(204, "", false, Map.of(), 1, Duration.ZERO).isSuccessful());
            assertTrue(new WebhookResponse(299, "", false, Map.of(), 1, Duration.ZERO).isSuccessful());
        }

        @Test
        void isSuccessfulRejectsEverythingElse() {
            assertFalse(new WebhookResponse(199, "", false, Map.of(), 1, Duration.ZERO).isSuccessful());
            assertFalse(new WebhookResponse(300, "", false, Map.of(), 1, Duration.ZERO).isSuccessful());
            assertFalse(new WebhookResponse(404, "", false, Map.of(), 1, Duration.ZERO).isSuccessful());
            assertFalse(new WebhookResponse(500, "", false, Map.of(), 1, Duration.ZERO).isSuccessful());
        }

        @Test
        void headersAreUnmodifiable() {
            WebhookResponse r = new WebhookResponse(200, "", false,
                    Map.of("X-Foo", List.of("bar")), 1, Duration.ZERO);
            assertThrows(UnsupportedOperationException.class,
                    () -> r.headers().put("X-Baz", List.of("qux")));
        }

        @Test
        void nullHeadersBecomeEmpty() {
            WebhookResponse r = new WebhookResponse(200, "", false, null, 1, Duration.ZERO);
            assertNotNull(r.headers());
            assertTrue(r.headers().isEmpty());
        }

        @Test
        void nullElapsedBecomesZero() {
            WebhookResponse r = new WebhookResponse(200, "", false, Map.of(), 1, null);
            assertEquals(Duration.ZERO, r.elapsed());
        }

        @Test
        void headerCopyIsDefensive() {
            var mutable = new java.util.LinkedHashMap<String, List<String>>();
            mutable.put("X-Foo", List.of("bar"));
            WebhookResponse r = new WebhookResponse(200, "", false, mutable, 1, Duration.ZERO);
            mutable.put("X-Baz", List.of("qux"));
            assertEquals(1, r.headers().size());
            assertFalse(r.headers().containsKey("X-Baz"));
        }
    }

    @Nested
    class WebhookResponseAsClass {

        private final ObjectMapper mapper = JsonMapper.builder().build();

        @Test
        void deserializesSimpleRecord() {
            WebhookResponse r = new WebhookResponse(200,
                    "{\"id\":\"abc\",\"code\":42}", false, Map.of(), 1, Duration.ZERO);
            Ack ack = r.as(Ack.class, mapper);
            assertEquals("abc", ack.id());
            assertEquals(42, ack.code());
        }

        @Test
        void deserializesIntoMap() {
            WebhookResponse r = new WebhookResponse(200,
                    "{\"x\":1,\"y\":\"z\"}", false, Map.of(), 1, Duration.ZERO);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = r.as(Map.class, mapper);
            assertEquals(1, map.get("x"));
            assertEquals("z", map.get("y"));
        }

        @Test
        void throwsOnNullType() {
            WebhookResponse r = new WebhookResponse(200, "{}", false, Map.of(), 1, Duration.ZERO);
            var ex = assertThrows(WebhookResponseException.class, () -> r.as(null, mapper));
            assertTrue(ex.getMessage().contains("type"));
        }

        @Test
        void throwsOnNullMapper() {
            WebhookResponse r = new WebhookResponse(200, "{}", false, Map.of(), 1, Duration.ZERO);
            var ex = assertThrows(WebhookResponseException.class, () -> r.as(Ack.class, null));
            assertTrue(ex.getMessage().contains("mapper"));
        }

        @Test
        void throwsOnNullBody() {
            WebhookResponse r = new WebhookResponse(204, null, false, Map.of(), 1, Duration.ZERO);
            var ex = assertThrows(WebhookResponseException.class, () -> r.as(Ack.class, mapper));
            assertTrue(ex.getMessage().contains("null"));
            assertTrue(ex.getMessage().contains("204"));
        }

        @Test
        void throwsOnInvalidJson() {
            WebhookResponse r = new WebhookResponse(200, "not json at all", false, Map.of(), 1, Duration.ZERO);
            var ex = assertThrows(WebhookResponseException.class, () -> r.as(Ack.class, mapper));
            assertTrue(ex.getMessage().contains(Ack.class.getName()));
            assertNotNull(ex.getCause());
        }

        @Test
        void throwsOnTypeMismatch() {
            WebhookResponse r = new WebhookResponse(200,
                    "{\"id\":\"abc\",\"code\":\"not-a-number\"}", false, Map.of(), 1, Duration.ZERO);
            assertThrows(WebhookResponseException.class, () -> r.as(Ack.class, mapper));
        }

        @Test
        void truncationMentionedInErrorMessage() {
            WebhookResponse r = new WebhookResponse(200,
                    "{\"id\":\"abc\",\"code\":", /* bodyTruncated */ true,
                    Map.of(), 1, Duration.ZERO);
            var ex = assertThrows(WebhookResponseException.class, () -> r.as(Ack.class, mapper));
            assertTrue(ex.getMessage().contains("truncated"),
                    "Message should hint that truncation is the likely cause: " + ex.getMessage());
        }

        record Ack(String id, int code) {
        }
    }

    @Nested
    class ExceptionShapes {

        @Test
        void deliveryExceptionCarriesUrlAttemptAndResponse() {
            WebhookResponse last = new WebhookResponse(503, "oops", false, Map.of(), 5, Duration.ofSeconds(10));
            var ex = new WebhookDeliveryException("exhausted", "https://x/hook", 5, last);
            assertEquals("exhausted", ex.getMessage());
            assertEquals("https://x/hook", ex.getUrl());
            assertEquals(5, ex.getAttemptCount());
            assertSame(last, ex.getLastResponse());
            assertNull(ex.getCause());
        }

        @Test
        void deliveryExceptionCarriesCause() {
            var cause = new java.io.IOException("boom");
            var ex = new WebhookDeliveryException("network error", "https://x/hook", 3, null, cause);
            assertNull(ex.getLastResponse());
            assertSame(cause, ex.getCause());
        }

        @Test
        void responseExceptionChaining() {
            var cause = new RuntimeException("inner");
            var ex = new WebhookResponseException("outer", cause);
            assertEquals("outer", ex.getMessage());
            assertSame(cause, ex.getCause());
        }
    }

    @Nested
    class DelayFormulaArithmetic {

        /**
         * The exponential formula lives in agent-core (not the API record), so this test only
         * verifies the fields that drive it are what the Javadoc promises: with DEFAULTS, the
         * documented sequence is 1 s → 2 s → 4 s → 8 s.
         */
        @Test
        void defaultsProduceDocumentedSequence() {
            RetryPolicy p = RetryPolicy.DEFAULTS;
            long[] expected = {1_000, 2_000, 4_000, 8_000};
            for (int attempt = 1; attempt <= expected.length; attempt++) {
                long base = (long) (p.initialDelay().toMillis() * Math.pow(p.multiplier(), attempt - 1));
                long capped = Math.min(base, p.maxDelay().toMillis());
                assertEquals(expected[attempt - 1], capped,
                        "attempt " + attempt + " base delay");
            }
        }

        @Test
        void maxDelayCapsRunawayExponential() {
            RetryPolicy p = new RetryPolicy(
                    20, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0.0,
                    Duration.ofSeconds(30),
                    RetryPolicy.DEFAULTS.retryableStatusCode(),
                    RetryPolicy.DEFAULTS.retryableException());
            for (int attempt = 5; attempt <= 20; attempt++) {
                long base = (long) (p.initialDelay().toMillis() * Math.pow(p.multiplier(), attempt - 1));
                long capped = Math.min(base, p.maxDelay().toMillis());
                assertTrue(capped <= p.maxDelay().toMillis());
            }
        }
    }
}
