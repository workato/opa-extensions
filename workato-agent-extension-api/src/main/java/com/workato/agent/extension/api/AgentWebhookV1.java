/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.api;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Version 1 of the webhook transport API that the agent provides to customer-developed extensions.
 *
 * <p>A thin wrapper over {@code POST} with automatic retry and exponential backoff — the single
 * outbound-HTTP primitive extensions use when they need to push an event to a recipe webhook URL
 * or wake a recipe trigger. Obtained from {@link AgentExtensionV1#webhook()}.
 *
 * <p>All API vocabulary — {@link WebhookResponse}, {@link RetryPolicy},
 * {@link WebhookDeliveryException}, {@link WebhookResponseException} — is declared as nested types
 * on this interface, so a single import brings in everything the extension needs:
 * <pre>{@code import com.workato.agent.extension.api.AgentWebhookV1;
 * import com.workato.agent.extension.api.AgentWebhookV1.WebhookResponse;
 * import com.workato.agent.extension.api.AgentWebhookV1.RetryPolicy;}</pre>
 *
 * <h2>send ladder</h2>
 * Four overloads, from the simplest to the most expressive — pick the one that carries exactly
 * the arguments you need:
 * <ul>
 *   <li>{@link #send(String, Object)} — url + JSON body, profile's effective policy, no headers;</li>
 *   <li>{@link #send(String, Object, Map)} — add custom headers (correlation IDs, auth tokens);</li>
 *   <li>{@link #send(String, Object, RetryPolicy)} — override the retry policy for one call;</li>
 *   <li>{@link #send(String, Object, RetryPolicy, Map)} — custom policy and headers.</li>
 * </ul>
 *
 * <h2>wakeup ladder</h2>
 * Same four-overload ladder for sending an empty POST (pull-mode trigger wake) —
 * {@link #wakeup(String)} through {@link #wakeup(String, RetryPolicy, Map)}. Analogous to the
 * agent's internal {@code WebhookSend.wakeup} that Kafka's {@code WakeupTriggerThread} uses.
 *
 * <h2>Delivery semantics</h2>
 * <ul>
 *   <li><b>at-least-once</b> — a request may be delivered more than once if the recipe answers
 *       with a retry'able status (5xx / 408 / 429 by default) or a network error occurs
 *       between the POST and the response.</li>
 *   <li><b>synchronous</b> — the calling thread blocks for the whole retry chain. Extensions that
 *       want asynchronous dispatch manage their own executor.</li>
 *   <li><b>only {@code application/json}</b> for {@link #send}. {@link #wakeup} sends an empty body
 *       ({@code Content-Length: 0}).</li>
 *   <li><b>thread-safe</b> — the implementation can be cached and called from any thread.</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>A delivered non-retry'able response (for example, 400 or 404) is <em>returned</em> as a
 *       {@link WebhookResponse} with {@link WebhookResponse#isSuccessful()} false — never thrown.
 *       The extension decides whether to treat it as fatal.</li>
 *   <li>A non-retry'able network error, or a retry'able error after all attempts have been
 *       exhausted, raises {@link WebhookDeliveryException}.</li>
 *   <li>{@link WebhookResponse#as(Class)} raises {@link WebhookResponseException} when the body
 *       cannot be parsed into the requested type — delivery succeeded but the payload is
 *       unusable. The two exception types are intentionally distinct so {@code catch} blocks
 *       can discriminate between "delivery failed" and "response unparseable".</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * The effective {@link RetryPolicy} for each extension profile is loaded from the
 * {@code webhook:} section of its YAML entry in {@code config.yml}; missing keys fall back to
 * {@link RetryPolicy#DEFAULTS}. See {@link #getDefaultRetryPolicy()} for introspection.
 *
 * <p>The {@code policy} overloads accept any {@link RetryPolicy} instance — fully custom
 * {@code new RetryPolicy(...)}, or a copy of {@link #getDefaultRetryPolicy()} with
 * {@link RetryPolicy#withAttempts(int)} / {@link RetryPolicy#withTimeout(Duration)} /
 * {@link RetryPolicy#withJitter(double)}.
 */
public interface AgentWebhookV1 {

    // =========================================================================================
    // send: deliver JSON payload (ladder from simplest to most expressive)
    // =========================================================================================

    /**
     * Deliver {@code jsonBody} as JSON to {@code url} using the profile's effective
     * {@link RetryPolicy}; no custom headers.
     *
     * @param url      target URL; non-null, parseable as a {@link java.net.URI}
     * @param jsonBody any Jackson-serializable value; {@code null} is encoded as {@code {}}
     * @return the final {@link WebhookResponse} — successful, or a non-retry'able non-2xx reply
     * @throws WebhookDeliveryException if all retries are exhausted or a non-retry'able network
     *                                  error happens; see class Javadoc for the error-handling model
     */
    WebhookResponse send(String url, Object jsonBody);

    /**
     * Deliver {@code jsonBody} to {@code url} with custom headers, using the profile's effective
     * {@link RetryPolicy}. {@code Content-Type} and {@code Content-Length} entries in
     * {@code headers} are ignored — the former is always {@code application/json}, the latter is
     * computed automatically.
     */
    WebhookResponse send(String url, Object jsonBody, Map<String, String> headers);

    /**
     * Deliver {@code jsonBody} to {@code url} with a per-call {@link RetryPolicy} override; no
     * custom headers. Useful when a specific call needs tighter timeouts, more attempts, or
     * different retry triggers than the profile default.
     */
    WebhookResponse send(String url, Object jsonBody, RetryPolicy policy);

    /**
     * Full control — per-call {@link RetryPolicy} and custom headers.
     */
    WebhookResponse send(String url, Object jsonBody, RetryPolicy policy, Map<String, String> headers);

    // =========================================================================================
    // wakeup: empty POST (pull-mode trigger wake)
    //         Same ladder as send(), minus the body parameter.
    // =========================================================================================

    /**
     * Send an empty {@code POST} to {@code url} (used to wake a pull-mode recipe trigger that will
     * then fetch the actual payload through its own endpoint). The request has
     * {@code Content-Length: 0} and no body at all — not even {@code {}}.
     */
    WebhookResponse wakeup(String url);

    /**
     * Empty POST with custom headers.
     */
    WebhookResponse wakeup(String url, Map<String, String> headers);

    /**
     * Empty POST with per-call {@link RetryPolicy}.
     */
    WebhookResponse wakeup(String url, RetryPolicy policy);

    /**
     * Full control — per-call {@link RetryPolicy} and custom headers.
     */
    WebhookResponse wakeup(String url, RetryPolicy policy, Map<String, String> headers);

    // =========================================================================================
    // introspection
    // =========================================================================================

    /**
     * The {@link RetryPolicy} the overloads without a {@code policy} parameter use for this
     * extension profile — the one assembled from the profile's YAML {@code webhook:} section
     * at startup, falling back to {@link RetryPolicy#DEFAULTS} for missing keys. Immutable;
     * safe to retain and share.
     */
    RetryPolicy getDefaultRetryPolicy();

    // =========================================================================================
    // Nested types — the entire API vocabulary. Implicitly `public static` because the
    // enclosing scope is an interface.
    // =========================================================================================

    /**
     * HTTP response captured by {@link #send} / {@link #wakeup} after the retry loop finishes.
     * The {@link #attemptCount()} includes retries; {@link #elapsed()} covers the whole chain
     * including inter-attempt sleeps.
     *
     * <p>The {@link #body()} is read into memory with a cap configured on the profile
     * ({@code webhook.response-cap-bytes}, default 1 MB). If the response was larger, the body is
     * truncated at the limit, a {@code ...[truncated at N bytes]} marker is appended, and
     * {@link #bodyTruncated()} returns {@code true}. The implementation also emits a WARN to the
     * agent log so operators can notice.
     *
     * <p>{@link #headers()} is an unmodifiable view; mutators throw
     * {@link UnsupportedOperationException}.
     */
    record WebhookResponse(
            int statusCode,
            String body,
            boolean bodyTruncated,
            Map<String, List<String>> headers,
            int attemptCount,
            Duration elapsed) {

        /**
         * Shared ObjectMapper used by {@link #as(Class)}. Jackson databind is a {@code compileOnly}
         * dependency of {@code extension-api} — at runtime the hosting Spring environment always
         * provides it, so extensions never need to shade it.
         *
         * <p>Default Jackson configuration is deliberate: no custom modules, no feature toggles.
         * An extension that needs a differently-tuned mapper (e.g., strict unknown-property
         * handling, Java-time module, custom deserializers) should call {@code as(String.class)}
         * and run the body through its own {@link ObjectMapper} — there is no API for swapping
         * this one out.
         */
        private static final ObjectMapper MAPPER = new ObjectMapper();

        /**
         * Canonical constructor — defensive-copies {@code headers} into an unmodifiable map and
         * normalises {@code elapsed} to {@link Duration#ZERO} when {@code null}, so instances are
         * safe to retain and compare.
         */
        public WebhookResponse {
            headers = headers == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            if (elapsed == null) {
                elapsed = Duration.ZERO;
            }
        }

        /**
         * {@code true} when {@link #statusCode()} is in the 2xx range.
         */
        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }

        /**
         * Deserialize {@link #body()} as JSON into the given type.
         *
         * <p>Suitable for simple record / POJO types (including {@code Map<String, Object>} via
         * {@code as(Map.class)}). For parameterised generic types ({@code List<Foo>},
         * {@code Map<String, Bar>}) use {@code as(String.class)} and parse the body yourself, or
         * wait for {@code as(TypeReference)} in a later release.
         *
         * @throws WebhookResponseException if {@link #body()} is {@code null}, is not valid JSON,
         *                                  or does not match the requested type. When
         *                                  {@link #bodyTruncated()} is {@code true} the exception
         *                                  message points at truncation as the likely cause.
         */
        public <T> T as(Class<T> type) {
            if (type == null) {
                throw new WebhookResponseException("type must not be null");
            }
            if (body == null) {
                throw new WebhookResponseException(
                        "Response body is null (status " + statusCode + "); nothing to deserialize");
            }
            try {
                return MAPPER.readValue(body, type);
            } catch (Exception e) {
                String hint = bodyTruncated
                        ? " (body was truncated — parsing a prefix will usually fail)"
                        : "";
                throw new WebhookResponseException(
                        "Failed to deserialize response body as " + type.getName() + hint, e);
            }
        }
    }

    /**
     * Retry configuration with exponential backoff and optional random jitter. Immutable record;
     * every mutating helper ({@link #withAttempts(int)}, {@link #withTimeout(Duration)},
     * {@link #withJitter(double)}) returns a new instance.
     *
     * <h2>Delay formula</h2>
     * For attempt {@code n} (1-based, delay applied <em>after</em> attempt {@code n} before
     * attempt {@code n+1}):
     * <pre>{@code
     * baseDelay(n)   = min(initialDelay * multiplier^(n-1), maxDelay)
     * actualDelay(n) = baseDelay(n) * (1 + random(-jitterFactor, +jitterFactor))
     *                  clamped to [0, 2*baseDelay(n)]
     * }</pre>
     * With {@link #DEFAULTS}: 1 s → 2 s → 4 s → 8 s → fail (four delays, five attempts).
     * {@code jitterFactor == 0.0} makes the sequence deterministic — handy for tests.
     *
     * <h2>Validation</h2>
     * The canonical constructor rejects obviously-nonsense configurations
     * ({@code maxAttempts < 1}, negative durations, {@code multiplier < 1.0},
     * {@code jitterFactor} outside {@code [0.0, 1.0]}, null predicates) with
     * {@link IllegalArgumentException}. The agent-side YAML loader applies a softer policy —
     * invalid values in {@code config.yml} log a WARN and fall back to {@link #DEFAULTS} for that
     * specific key, so a typo does not keep the extension from loading.
     */
    record RetryPolicy(
            int maxAttempts,
            Duration initialDelay,
            double multiplier,
            Duration maxDelay,
            double jitterFactor,
            Duration requestTimeout,
            IntPredicate retryableStatusCode,
            Predicate<IOException> retryableException) {

        /**
         * Baseline policy applied when a profile does not customise any webhook settings.
         * Five attempts, exponential 1 s → 2 s → 4 s → 8 s (no jitter), 30 s per-attempt timeout,
         * retrying on 5xx / 408 / 429 and on all {@link IOException}s.
         */
        public static final RetryPolicy DEFAULTS = new RetryPolicy(
                5,
                Duration.ofSeconds(1),
                2.0,
                Duration.ofSeconds(30),
                0.0,
                Duration.ofSeconds(30),
                s -> s >= 500 || s == 408 || s == 429,
                e -> true);

        /**
         * Canonical constructor — validates all fields; see class Javadoc for the rules.
         */
        public RetryPolicy {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
            }
            if (initialDelay == null || initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must be non-negative, got " + initialDelay);
            }
            if (!(multiplier >= 1.0) || Double.isNaN(multiplier) || Double.isInfinite(multiplier)) {
                throw new IllegalArgumentException("multiplier must be a finite value >= 1.0, got " + multiplier);
            }
            if (maxDelay == null || maxDelay.isNegative()) {
                throw new IllegalArgumentException("maxDelay must be non-negative, got " + maxDelay);
            }
            if (!(jitterFactor >= 0.0 && jitterFactor <= 1.0) || Double.isNaN(jitterFactor)) {
                throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0], got " + jitterFactor);
            }
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("requestTimeout must be positive, got " + requestTimeout);
            }
            if (retryableStatusCode == null) {
                throw new IllegalArgumentException("retryableStatusCode must not be null");
            }
            if (retryableException == null) {
                throw new IllegalArgumentException("retryableException must not be null");
            }
        }

        /**
         * Copy of this policy with a different {@link #maxAttempts()}.
         */
        public RetryPolicy withAttempts(int n) {
            return new RetryPolicy(n, initialDelay, multiplier, maxDelay, jitterFactor,
                    requestTimeout, retryableStatusCode, retryableException);
        }

        /**
         * Copy of this policy with a different {@link #requestTimeout()}.
         */
        public RetryPolicy withTimeout(Duration t) {
            return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, jitterFactor,
                    t, retryableStatusCode, retryableException);
        }

        /**
         * Copy of this policy with a different {@link #jitterFactor()}.
         */
        public RetryPolicy withJitter(double factor) {
            return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, factor,
                    requestTimeout, retryableStatusCode, retryableException);
        }
    }

    /**
     * Thrown when delivery exhausts all retry attempts, or when a non-retry'able network error
     * occurs. Carries the target URL, the number of attempts that were made, and — when the final
     * failure was an HTTP response rather than an exception — the last {@link WebhookResponse}
     * for diagnostics.
     *
     * <p>Unchecked, in the spirit of Spring's {@code RestClientException}: extensions may catch it
     * but are not required to declare {@code throws}.
     */
    final class WebhookDeliveryException extends RuntimeException {

        private final String url;
        private final int attemptCount;
        private final WebhookResponse lastResponse;

        public WebhookDeliveryException(String message, String url, int attemptCount, WebhookResponse lastResponse) {
            super(message);
            this.url = url;
            this.attemptCount = attemptCount;
            this.lastResponse = lastResponse;
        }

        public WebhookDeliveryException(String message, String url, int attemptCount,
                                        WebhookResponse lastResponse, Throwable cause) {
            super(message, cause);
            this.url = url;
            this.attemptCount = attemptCount;
            this.lastResponse = lastResponse;
        }

        /**
         * URL the agent was trying to reach.
         */
        public String getUrl() {
            return url;
        }

        /**
         * Number of HTTP attempts actually made (1-based; equals {@code maxAttempts} on exhaustion).
         */
        public int getAttemptCount() {
            return attemptCount;
        }

        /**
         * The final HTTP response, when the chain ended with an HTTP status (for example, five
         * consecutive 503s). {@code null} when the final failure was a network error or interrupt.
         */
        public WebhookResponse getLastResponse() {
            return lastResponse;
        }
    }

    /**
     * Thrown by {@link WebhookResponse#as(Class)} when the response body is {@code null}, is not
     * valid JSON, or does not match the requested type. Distinct from
     * {@link WebhookDeliveryException} so extensions can tell "delivery failed" from "response
     * unparseable" in a {@code catch} block.
     *
     * <p>Unchecked, same reasoning as {@link WebhookDeliveryException}.
     */
    final class WebhookResponseException extends RuntimeException {

        public WebhookResponseException(String message) {
            super(message);
        }

        public WebhookResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
