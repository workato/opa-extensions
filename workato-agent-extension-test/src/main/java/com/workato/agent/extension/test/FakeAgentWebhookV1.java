/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentWebhookV1;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Fake {@link AgentWebhookV1} suitable for standalone unit / integration tests of a customer
 * extension. Captures every delivery attempt in a call log the test can assert against, and lets
 * the test stub HTTP outcomes (specific status / body, network failure, scripted sequences)
 * without opening a real socket.
 *
 * <h2>Fidelity contract</h2>
 * The fake honours the full {@link RetryPolicy}: {@code maxAttempts}, {@code retryableStatusCode},
 * {@code retryableException}, exhaustion → {@link WebhookDeliveryException}. The one deliberate
 * deviation from production is that <b>all delays are zero</b> — tests must not spend real time
 * sleeping between attempts. The call log records {@link Duration#ZERO} for {@code elapsed}; order
 * and count of attempts is preserved exactly as production would produce it.
 *
 * <h2>Default behaviour</h2>
 * Without any stubs, every {@link #send} / {@link #wakeup} returns a canned
 * {@code WebhookResponse(200, "{}", false, {}, 1, ZERO)} on the first attempt — enough for tests
 * that only care that the extension <em>attempted</em> delivery.
 *
 * <h2>Stub resolution</h2>
 * When more than one stub matches a given {@code (url, kind)} tuple the <b>most recently
 * registered</b> one wins (last-wins). This matches typical test ergonomics: a {@code @BeforeEach}
 * registers a broad default, individual {@code @Test} methods override with a narrower stub.
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * FakeAgentWebhookV1 webhook = harness.webhook();
 *
 * // Scenario: recipe answers 503 twice, then 200.
 * webhook.whenSent("https://recipe/webhook")
 *     .thenReturnSequence(
 *         WebhookResponses.of(503, "retry me"),
 *         WebhookResponses.of(503, "retry me"),
 *         WebhookResponses.of(200, "{\"id\":\"abc\"}"));
 *
 * harness.mockMvc().perform(agent.request(MyController::fire));
 *
 * assertEquals(3, webhook.callsFor("https://recipe/webhook").size());
 * assertEquals("abc", webhook.lastCall().response().as(Ack.class).id());
 * }</pre>
 */
public final class FakeAgentWebhookV1 implements AgentWebhookV1 {

    private final List<SentCall> calls = new CopyOnWriteArrayList<>();
    private final List<StubEntry> stubs = new CopyOnWriteArrayList<>();
    private volatile RetryPolicy defaultPolicy = RetryPolicy.DEFAULTS;

    // ----- send ladder (AgentWebhookV1) -----------------------------------------------------
    // Short overloads delegate to the 4-arg "root" so headers/policy normalisation lives in one place.

    private static Predicate<String> matchExact(String url) {
        if (url == null) {
            throw new IllegalArgumentException("url must not be null");
        }
        return url::equals;
    }

    private static WebhookResponse withAttemptCount(WebhookResponse base, int attempt) {
        return new WebhookResponse(base.statusCode(), base.body(), base.bodyTruncated(),
                base.headers(), attempt, Duration.ZERO);
    }

    private static StubOutcome defaultOutcome() {
        return StubOutcome.ofResponse(new WebhookResponse(200, "{}", false, Map.of(), 1, Duration.ZERO));
    }

    private static Map<String, List<String>> safeHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>(headers.size());
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            List<String> values = e.getValue();
            copy.put(e.getKey(), values == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(values)));
        }
        return Collections.unmodifiableMap(copy);
    }

    // ----- wakeup ladder (AgentWebhookV1) ---------------------------------------------------

    private static RetryPolicy requireNonNullPolicy(RetryPolicy p) {
        if (p == null) {
            throw new IllegalArgumentException(
                    "RetryPolicy must not be null; use the overload without the policy parameter instead");
        }
        return p;
    }

    @Override
    public WebhookResponse send(String url, Object jsonBody) {
        return send(url, jsonBody, defaultPolicy, Map.of());
    }

    @Override
    public WebhookResponse send(String url, Object jsonBody, Map<String, List<String>> headers) {
        return send(url, jsonBody, defaultPolicy, headers);
    }

    @Override
    public WebhookResponse send(String url, Object jsonBody, RetryPolicy policy) {
        return send(url, jsonBody, policy, Map.of());
    }

    // ----- introspection (AgentWebhookV1) ---------------------------------------------------

    @Override
    public WebhookResponse send(String url, Object jsonBody, RetryPolicy policy, Map<String, List<String>> headers) {
        if (jsonBody == null) {
            throw new IllegalArgumentException(
                    "jsonBody must not be null; pass Map.of() or \"\" explicitly for an empty payload");
        }
        return executeWithRetry(CallKind.SEND, url, jsonBody, safeHeaders(headers), requireNonNullPolicy(policy));
    }

    @Override
    public WebhookResponse wakeup(String url) {
        return wakeup(url, defaultPolicy, Map.of());
    }

    // ----- call observation --------------------------------------------------------------------

    @Override
    public WebhookResponse wakeup(String url, Map<String, List<String>> headers) {
        return wakeup(url, defaultPolicy, headers);
    }

    @Override
    public WebhookResponse wakeup(String url, RetryPolicy policy) {
        return wakeup(url, policy, Map.of());
    }

    @Override
    public WebhookResponse wakeup(String url, RetryPolicy policy, Map<String, List<String>> headers) {
        return executeWithRetry(CallKind.WAKEUP, url, null, safeHeaders(headers), requireNonNullPolicy(policy));
    }

    @Override
    public RetryPolicy getDefaultRetryPolicy() {
        return defaultPolicy;
    }

    /**
     * Replace the retry policy returned by {@link #getDefaultRetryPolicy()} and used by the
     * overloads that do not take an explicit {@code policy} argument. Lets a test mimic a profile
     * whose {@code webhook:} YAML section customises retries.
     */
    public void setDefaultRetryPolicy(RetryPolicy defaultPolicy) {
        if (defaultPolicy == null) {
            throw new IllegalArgumentException("defaultPolicy must not be null");
        }
        this.defaultPolicy = defaultPolicy;
    }

    // ----- stub registration -------------------------------------------------------------------

    /**
     * All attempts captured so far, in order (oldest first). Snapshot — safe to iterate.
     */
    public List<SentCall> allCalls() {
        return List.copyOf(calls);
    }

    /**
     * All attempts targeting the given URL, in order.
     */
    public List<SentCall> callsFor(String url) {
        List<SentCall> out = new ArrayList<>();
        for (SentCall c : calls) {
            if (c.url().equals(url)) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /**
     * All attempts of the given kind (SEND or WAKEUP), in order.
     */
    public List<SentCall> callsOfKind(CallKind kind) {
        List<SentCall> out = new ArrayList<>();
        for (SentCall c : calls) {
            if (c.kind() == kind) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The last captured attempt. {@link IllegalStateException} if no calls have been made —
     * use this when the test expects at least one delivery.
     */
    public SentCall lastCall() {
        if (calls.isEmpty()) {
            throw new IllegalStateException("No calls have been captured");
        }
        return calls.get(calls.size() - 1);
    }

    // ----- retry engine ------------------------------------------------------------------------

    /**
     * Clear the call log and all stubs. The default retry policy stays as last set.
     */
    public void reset() {
        calls.clear();
        stubs.clear();
    }

    /**
     * Match any {@link CallKind} delivered to the exact {@code url}.
     */
    public StubBuilder whenCalled(String url) {
        return new StubBuilderImpl(matchExact(url), null);
    }

    // ----- static helpers ----------------------------------------------------------------------

    /**
     * Match any {@link CallKind} whose URL satisfies {@code matcher}.
     */
    public StubBuilder whenCalledMatching(Predicate<String> matcher) {
        return new StubBuilderImpl(matcher, null);
    }

    /**
     * Match {@link CallKind#SEND} with the exact {@code url}.
     */
    public StubBuilder whenSent(String url) {
        return new StubBuilderImpl(matchExact(url), CallKind.SEND);
    }

    /**
     * Match {@link CallKind#WAKEUP} with the exact {@code url}.
     */
    public StubBuilder whenWokenUp(String url) {
        return new StubBuilderImpl(matchExact(url), CallKind.WAKEUP);
    }

    private WebhookResponse executeWithRetry(CallKind kind, String url, Object body,
                                             Map<String, List<String>> headers, RetryPolicy policy) {
        if (url == null) {
            throw new IllegalArgumentException("url must not be null");
        }
        StubEntry stub = findStub(url, kind);
        WebhookResponse lastResponse = null;

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            StubOutcome outcome = stub == null ? defaultOutcome() : stub.outcomeFor(attempt);
            SentCall call;

            if (outcome.exception() != null) {
                IOException cause = outcome.exception();
                call = new SentCall(kind, url, body, headers, policy, Instant.now(), attempt, null, cause);
                calls.add(call);
                if (!policy.retryableException().test(cause)) {
                    throw new WebhookDeliveryException(
                            "Non-retryable network error on attempt " + attempt + " to " + url,
                            url, attempt, null, cause);
                }
                lastResponse = null;
                continue;
            }

            WebhookResponse response = withAttemptCount(outcome.response(), attempt);
            call = new SentCall(kind, url, body, headers, policy, Instant.now(), attempt, response, null);
            calls.add(call);
            lastResponse = response;

            if (response.isSuccessful()) {
                return response;
            }
            if (!policy.retryableStatusCode().test(response.statusCode())) {
                return response;
            }
            // retryable non-success → next iteration
        }

        throw new WebhookDeliveryException(
                "Exhausted " + policy.maxAttempts() + " attempt(s) to " + url,
                url, policy.maxAttempts(), lastResponse);
    }

    private StubEntry findStub(String url, CallKind kind) {
        // last-wins: iterate from the most recently registered backward and pick the first match
        for (int i = stubs.size() - 1; i >= 0; i--) {
            StubEntry s = stubs.get(i);
            if (s.matches(url, kind)) {
                return s;
            }
        }
        return null;
    }

    // ===== Nested types ========================================================================

    /**
     * Which API method produced the captured call.
     */
    public enum CallKind {
        /**
         * {@link AgentWebhookV1#send} family.
         */
        SEND,
        /**
         * {@link AgentWebhookV1#wakeup} family.
         */
        WAKEUP
    }

    /**
     * Fluent response configuration for a stub matched during {@link #whenCalled} / siblings.
     */
    public interface StubBuilder {

        /**
         * Return the same {@code (statusCode, body)} on every matching call. Headers of the fake
         * response are empty; use {@link #thenReturn(WebhookResponse)} to supply them.
         */
        void thenReturn(int statusCode, String body);

        /**
         * Return the same {@link WebhookResponse} on every matching call. The {@code attemptCount}
         * field is overwritten with the actual attempt number so the fake stays consistent with
         * production counting.
         */
        void thenReturn(WebhookResponse response);

        /**
         * Fail every matching attempt with the same {@link IOException}. Combined with a retryable
         * exception predicate on the policy, retries continue until exhaustion.
         */
        void thenFail(IOException cause);

        /**
         * Shorthand for {@link #thenFail(IOException)} with a generic cause — convenient when the
         * test does not care about the exact exception type.
         */
        void thenFailAll();

        /**
         * Return each supplied {@link WebhookResponse} on the matching 1st, 2nd, … attempt. If more
         * attempts occur than entries in the sequence, the last entry is reused. Useful for
         * modelling "two failures, then success" scenarios.
         */
        void thenReturnSequence(WebhookResponse... responses);
    }

    @FunctionalInterface
    private interface AttemptHandler {
        StubOutcome handle(int attempt);
    }

    /**
     * One captured delivery attempt. Each HTTP attempt is a separate {@code SentCall}; a single
     * extension call that retried five times produces five {@code SentCall}s with the same
     * {@code url}/{@code body}/{@code headers}/{@code policy} and increasing {@code attemptNumber}.
     *
     * <p>Exactly one of {@link #response()} / {@link #exception()} is non-null — the HTTP response
     * if the attempt completed, or the simulated IO exception if the stub answered with
     * {@link StubBuilder#thenFail(IOException)} / {@link StubBuilder#thenFailAll()}.
     */
    public record SentCall(
            CallKind kind,
            String url,
            Object body,
            Map<String, List<String>> headers,
            RetryPolicy policy,
            Instant timestamp,
            int attemptNumber,
            WebhookResponse response,
            Throwable exception) {
    }

    private record StubEntry(Predicate<String> urlMatcher,
                             CallKind kindFilter,
                             AttemptHandler handler) {

        boolean matches(String url, CallKind kind) {
            if (kindFilter != null && kindFilter != kind) {
                return false;
            }
            return urlMatcher.test(url);
        }

        StubOutcome outcomeFor(int attempt) {
            return handler.handle(attempt);
        }
    }

    private record StubOutcome(WebhookResponse response, IOException exception) {

        static StubOutcome ofResponse(WebhookResponse r) {
            return new StubOutcome(r, null);
        }

        static StubOutcome ofException(IOException e) {
            return new StubOutcome(null, e);
        }
    }

    private final class StubBuilderImpl implements StubBuilder {

        private final Predicate<String> urlMatcher;
        private final CallKind kindFilter;    // null means "any kind"

        StubBuilderImpl(Predicate<String> urlMatcher, CallKind kindFilter) {
            this.urlMatcher = urlMatcher;
            this.kindFilter = kindFilter;
        }

        @Override
        public void thenReturn(int statusCode, String body) {
            WebhookResponse canned = new WebhookResponse(statusCode, body, false, Map.of(), 1, Duration.ZERO);
            stubs.add(new StubEntry(urlMatcher, kindFilter, attempt -> StubOutcome.ofResponse(canned)));
        }

        @Override
        public void thenReturn(WebhookResponse response) {
            if (response == null) {
                throw new IllegalArgumentException("response must not be null");
            }
            stubs.add(new StubEntry(urlMatcher, kindFilter, attempt -> StubOutcome.ofResponse(response)));
        }

        @Override
        public void thenFail(IOException cause) {
            if (cause == null) {
                throw new IllegalArgumentException("cause must not be null");
            }
            stubs.add(new StubEntry(urlMatcher, kindFilter, attempt -> StubOutcome.ofException(cause)));
        }

        @Override
        public void thenFailAll() {
            thenFail(new IOException("simulated network failure (FakeAgentWebhookV1)"));
        }

        @Override
        public void thenReturnSequence(WebhookResponse... responses) {
            if (responses == null || responses.length == 0) {
                throw new IllegalArgumentException("responses must contain at least one entry");
            }
            // Defensive copy so later mutations of the caller's array do not leak in.
            WebhookResponse[] snapshot = responses.clone();
            for (WebhookResponse r : snapshot) {
                if (r == null) {
                    throw new IllegalArgumentException("responses must not contain null entries");
                }
            }
            stubs.add(new StubEntry(urlMatcher, kindFilter, attempt -> {
                int idx = Math.min(attempt - 1, snapshot.length - 1);
                return StubOutcome.ofResponse(snapshot[idx]);
            }));
        }
    }
}
