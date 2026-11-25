# Extension API Module

Public API that customer-developed agent extensions compile against.

> **Workspace note.** This module was moved out of the OPA repo
> (`~/Projects/opa/modules/extension-api/`) into the standalone
> `opa-extensions` workspace. Cross-references below to `modules/agent-core/…`
> or `modules/agent-launcher/…` point into the OPA repo (`~/Projects/opa/`),
> which consumes this module as a published artifact (Nexus publishing is
> future work — see root `CLAUDE.md`).

> **Companion customer-facing doc: `README.md` in this directory.**
> This CLAUDE.md is the *maintainer* view (invariants, implementation seams,
> module layout). README.md is the *consumer* view (how to write an extension,
> API reference, migration from the legacy types, troubleshooting).
>
> **Keep them in sync.** Any API change in this package — new interface,
> renamed method, new deprecation, new return type, changed semantics — must
> be reflected in README.md in the same change. Specifically, update:
>
> - the **API Reference** section (new/changed methods, their contracts);
> - the **Migrating from the legacy API** table if the mapping changes or a
    > legacy type is removed;
> - the **Troubleshooting** table if a new failure mode becomes customer-visible;
> - the **Versioning** section when introducing a `V2` or a removal window.
>
> A PR that touches `AgentExtensionV1`, `AgentExtensionContextV1` (including its nested
> `AgentConfiguration` and `ConnectionProperties`), `AgentWebhookV1` (including its nested
> `WebhookResponse` / `RetryPolicy` / `WebhookDeliveryException` /
> `WebhookResponseException`), `AgentToolsV1`, or `package-info.java` without touching
> README.md is almost certainly incomplete.

> **Keep `extension-test` in sync.** The `extension-test` module ships the
> customer-facing fakes/harness that mock this API without the agent. Every change
> here must be mirrored there in the same PR. Behavioural changes to
> `AgentWebhookV1` additionally require updating the executable paritet suite
> `WebhookApiProductionParityTest` in `modules/agent-launcher/src/test/java/com/workato/agent/extension/`
> — the test is the guarantee that `FakeAgentWebhookV1` and the production
> `AgentWebhookV1Impl` stay observably identical. A silent drift between them
> would mean extensions pass harness tests and fail production (or the reverse):
>
> - **Mock implementations.** `extension-test/.../FakeAgentExtension.java`
    > (and any sibling fakes) must implement the new / renamed method, honour
    > the new contract (e.g., unmodifiable guarantees), and expose the new
    > return type. A new interface in this package requires a new fake class
    > there.
> - **Harness surface.** If a new type becomes autowire-visible to extensions
    > (another `"agentExtension"`-style bean, say), `AgentExtensionHarness` must
    > register it so customer tests see the same bean graph the production
    > `EndpointBeanRegistrar` wires up.
> - **Fake-contract tests.** `extension-test/src/test/java/.../*Test.java`
    > must gain or update a test that pins the new behaviour on the fake:
    > that the new method returns the expected value, that mutating a new
    > collection view throws, that overloading a method ref still works, etc.
    > These tests are the executable guarantee that production and test
    > semantics match.
> - **Fake-side docs.** `extension-test/.../README.md` (customer-facing) and
    > `extension-test/.../CLAUDE.md` (maintainer) must reflect the API delta
    > in their "API Reference" / "Load-bearing design decisions" sections.
>
> A PR that extends `AgentExtensionV1` with a new method but leaves
> `extension-test` untouched will silently drift — customer tests continue to
> compile against the old fake until a customer notices in the field.

## Purpose

Customer extension JARs (deployed into `lib_ext/` and routed under `/ext/{name}/*`)
need a stable compile-time contract that does not drag in agent internals. This
module exists to be that contract — one place a customer can add as a Gradle /
Maven dependency and get the types they are allowed to depend on.

## Invariant: no agent dependencies

This module **must not** declare `project(":...")` dependencies on any other agent
module (`agent-core`, `connector-api`, etc.). External libraries are allowed if
genuinely needed, but should be kept minimal — every dependency added here is a
dependency every customer JAR inherits.

Current state: zero *mandatory agent-side* runtime dependencies. **Jackson databind** is a
`compileOnly` dependency, used by (a) `AgentToolsV1.objectMapper()` as the return type exposed
to extensions and (b) `AgentWebhookV1.WebhookResponse.as(Class<T>, ObjectMapper)` as a
parameter type. The API module itself never instantiates an `ObjectMapper` — the mapper is
always supplied by the agent (via `AgentToolsV1`) or by the caller. The hosting Spring
environment is assumed to provide Jackson on the runtime classpath (it does — Spring MVC pulls
it in). Extensions must not shade Jackson in their JARs.

If a future API addition needs another library, add it as `compileOnly` only when the hosting
environment is guaranteed to provide it at runtime; otherwise rewrite the API to avoid the
dependency. Any **non-compileOnly** runtime dependency added here is a breaking change for every
existing extension JAR classloader.

## API Surface (V1)

Five interfaces plus the four nested types inside `AgentWebhookV1`, all under
`com.workato.agent.extension.api`. The implementation is supplied by `agent-core` and registered
per-endpoint by `EndpointBeanRegistrar`; customer controllers obtain it via Spring injection:

```java

@RestController
public class MyController {
    @Autowired
    AgentExtensionV1 agent;    // the entry point
}
```

### `AgentExtensionV1` — entry point

The bean customer controllers `@Autowired`. One instance per extension (lives in
each extension's isolated Spring context), so `getContext()` always reflects the
endpoint owning the current request.

```java
public interface AgentExtensionV1 {
    AgentExtensionContextV1 getContext();

    AgentWebhookV1 webhook();          // NEW in V1

    AgentToolsV1 getTools();           // NEW in V1
}
```

Bean name: `"agentExtension"` (not load-bearing — customers autowire by type).

**`webhook()` and `getTools()` are deliberately abstract**, no `default`. V1 has not yet been
through its customer-visible release, so forcing all current and future `AgentExtensionV1`
implementations to supply both is cheap today and prevents customers from hand-rolling a
half-built fake tomorrow. Both known implementations — `AgentExtensionV1Impl` in `agent-core`
and `FakeAgentExtension` in `extension-test` — are kept in lockstep.

### `AgentExtensionContextV1` — per-endpoint context

Replaces the legacy `com.workato.agent.config.DefaultExtensionEndpoint` record
(bean `"workatoEndpoint"`). Carries the endpoint's identity, its own YAML
section, and the connection-scoped properties attached to the current request.

```java
public interface AgentExtensionContextV1 {
    String getProfileName();                                // this endpoint's YAML key

    AgentConfiguration getProfileConfiguration();           // this endpoint's YAML section

    ConnectionProperties getConnectionProperties();         // this endpoint's connection payload
}
```

- `getProfileName()` — the key the extension is declared under in `config.yml`
  (e.g., `my-extension:` → `"my-extension"`). Stable for the lifetime of the
  agent process.
- `getProfileConfiguration()` — the whole YAML section verbatim, including the
  `controller` / `controllerClass` key alongside custom properties. Snapshot
  taken when the endpoint was registered — stable across YAML reloads of the agent.
  Returned instance is an **unmodifiable view** backed by a defensive copy;
  mutators throw `UnsupportedOperationException`.
- `getConnectionProperties()` — the current connection payload (OAuth tokens,
  per-tenant base URLs, credentials that must not live in a YAML file). Also
  unmodifiable and defensive-copy-backed. Adds `contentHash()` — a stable
  SHA-256 hex fingerprint of the content, suitable as a cache key when state is
  derived from the current connection. Every entry is also resolvable via
  `@Value("${key}")` on the controller, alongside the profile entries — see
  the next sub-section for the collision rule.

#### Deliberately omitted: `getAllConfigurations()`

An earlier draft of V1 exposed a third method returning every loaded
extension's YAML section as a `Map<String, AgentConfiguration>`. It was
removed before release and must **not** be re-added without an explicit
threat-model review.

Rationale: in multi-tenant agent deployments where extensions come from different
third parties, that method would let any one compromised extension exfiltrate
every other extension's YAML — including secrets stored in those sections. The
`V1` contract therefore keeps each context's view strictly to its own profile.

This is a partial mitigation, not full isolation. The extension's Spring
context has the main agent `ApplicationContext` as its parent, so an extension
can still autowire agent beans (e.g., `ServerConfig`) and reach other
extensions' data through them. True cross-extension isolation requires
restricting the per-endpoint Spring context's bean visibility to the API
surface (`AgentExtensionV1` and whatever is explicitly whitelisted) and
excluding the main agent `ApplicationContext` from the parent chain for
untrusted extensions. Out of scope for V1; must be in place before any
"all extensions" accessor is reconsidered.

### `AgentExtensionContextV1.AgentConfiguration` — map-shaped view of a YAML section

Declared as a nested interface on `AgentExtensionContextV1` so that one import brings in both
the context and its configuration type (same style we use for `AgentWebhookV1`'s nested
value types).

```java
public interface AgentConfiguration extends Map<String, Object> {
}
```

Deliberately a bare `Map` extension — the configuration *is* a map. Customers
iterate, look up by key, and stream using the standard JDK API without a new
vocabulary. Each instance returned by the API is an unmodifiable view backed
by a defensive copy: any mutating operation throws
`UnsupportedOperationException`, and the agent's internal state is unaffected.

Future typed accessors (`getString`, `getInt`, …) can be added to this
interface without breaking source or binary compatibility — the `Map` contract
on the type stays in place.

### `AgentExtensionContextV1.ConnectionProperties` — map-shaped view of the connection payload

Declared next to `AgentConfiguration`, same nesting rationale (single-import).

```java
public interface ConnectionProperties extends Map<String, Object> {
    String contentHash();
}
```

Semantically distinct from `AgentConfiguration` in two ways:

1. **Data origin.** `AgentConfiguration` is the YAML section — static,
   administrator-authored, typically public-safe. `ConnectionProperties` carries
   the per-request connection payload — OAuth tokens, per-tenant URLs,
   credentials that must not land in YAML. Both are map-shaped so extensions
   consume them with the same vocabulary.
2. **`contentHash()`.** Extensions often derive cached state (schemas,
   negotiated capabilities, HTTP clients) from the connection payload. The
   SHA-256 hex fingerprint is a stable cache key: identical content → identical
   hash, across JVM instances and restarts; any mutation → different hash. The
   canonical form fed to SHA-256 is `key=value\n` lines sorted lexicographically
   by key, values coerced through `String.valueOf(...)`. Documented as part of
   the public contract, so the production impl and `FakeAgentExtension` produce
   bit-identical hashes for the same input.

Same unmodifiable guarantee as `AgentConfiguration`. Collision semantics with
the profile source are a property of the `@Value` resolution surface, not of
the map itself — the map always reports what the caller sent verbatim:

- When `@Value("${k}")` is resolved and `k` exists in both the profile section
  and the connection payload, **the profile value wins** and the agent emits
  an `ERROR`-level log entry naming the shadowed key. Rationale: profile
  properties are administrator-trust (they live in `config.yml`, which only the
  agent operator edits); connection properties are caller-trust (they come in
  with a request and should not be able to override admin-sanctioned config).
- The connection map still carries the value verbatim, because extensions may
  legitimately read it directly (e.g., log the raw value to prove what was
  sent) — only the Spring-resolved `@Value` surface prefers the profile.

Both `AgentExtensionV1Impl` (agent-core) and `FakeAgentExtension`
(extension-test) **must** honour this rule identically; the contract test on
both sides is the executable guarantee. Drift between them is a correctness
bug, not a style issue.

### `AgentToolsV1` — agent-provided utilities bag

```java
public interface AgentToolsV1 {
    ObjectMapper objectMapper();    // tools.jackson.databind.ObjectMapper, agent's shared instance
}
```

Returned instances are **references** to objects the agent itself owns — not copies. Jackson's
`ObjectMapper` is documented as thread-safe, so sharing is safe; but mutation (`registerModule`,
feature toggles) leaks into every other extension and the agent itself. Extensions needing a
customised mapper must call `ObjectMapper.copy()` first.

Why a bag (vs. direct `agent.objectMapper()` on `AgentExtensionV1`): keeps the `AgentExtensionV1`
surface stable as the agent decides to expose more infrastructure (future candidates: an HTTP
client, a scheduler, a metrics registry) — new additions land on `AgentToolsV1` without
touching every extension's autowire site. The `getTools()` getter is the single evolution
seam.

The implementation on the agent side lives next to `AgentExtensionV1Impl` in `agent-core`
(same `com.workato.agent.extension` package). The fake implementation in
`extension-test` constructs a fresh `JsonMapper.builder().build()` — for a test fixture the
agent's configuration is not available (and would need to be, if we grew tool surface that
genuinely required it).

### `AgentWebhookV1` — outbound HTTP transport

Thin wrapper over `POST` with automatic retry, exponential backoff and body-cap truncation.
Obtained from `AgentExtensionV1.webhook()`. Nested types (`WebhookResponse`,
`RetryPolicy`, `WebhookDeliveryException`, `WebhookResponseException`) are declared on the
interface itself — the whole API vocabulary lives in one file.

```java
public interface AgentWebhookV1 {
    // send ladder — pick the shortest overload that carries exactly what you need
    WebhookResponse send(String url, Object jsonBody);

    WebhookResponse send(String url, Object jsonBody, Map<String, String> headers);

    WebhookResponse send(String url, Object jsonBody, RetryPolicy policy);

    WebhookResponse send(String url, Object jsonBody, RetryPolicy policy, Map<String, String> headers);

    // wakeup ladder — empty POST, same four shapes
    WebhookResponse wakeup(String url);

    WebhookResponse wakeup(String url, Map<String, String> headers);

    WebhookResponse wakeup(String url, RetryPolicy policy);

    WebhookResponse wakeup(String url, RetryPolicy policy, Map<String, String> headers);

    RetryPolicy getDefaultRetryPolicy();

    record WebhookResponse(int statusCode, String body, boolean bodyTruncated,
                           Map<String, List<String>> headers, int attemptCount, Duration elapsed) {
        public boolean isSuccessful();

        public <T> T as(Class<T> type, ObjectMapper mapper);  // caller-supplied mapper; throws WebhookResponseException
    }

    record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier,
                       Duration maxDelay, double jitterFactor, Duration requestTimeout,
                       IntPredicate retryableStatusCode, Predicate<IOException> retryableException) {
        public static final RetryPolicy DEFAULTS = /* 5 attempts, 1s/2x/30s, no jitter, 30s timeout, 5xx/408/429 */;

        public RetryPolicy withAttempts(int n);

        public RetryPolicy withTimeout(Duration t);

        public RetryPolicy withJitter(double factor);
    }

    final class WebhookDeliveryException extends RuntimeException {
        public String getUrl();

        public int getAttemptCount();

        public WebhookResponse getLastResponse();  // null on terminal network error
    }

    final class WebhookResponseException extends RuntimeException {
    }
}
```

**Delivery contract:** at-least-once, synchronous, JSON-only. Non-retryable non-2xx is
*returned* as a `WebhookResponse` for the extension to interpret; only exhaustion or a
non-retryable network error throws `WebhookDeliveryException`.

**Config via YAML (optional):** the `webhook:` subsection of a profile customises defaults
(`retries`, `initial-delay-ms`, `multiplier`, `max-delay-ms`, `jitter-factor`, `timeout-ms`,
`response-cap-bytes`). Missing keys → `RetryPolicy.DEFAULTS` + 1 MB cap. Invalid values →
WARN + per-key fallback.

**Metrics (operator-facing, not customer-facing):** the agent-core impl emits
`opa.webhook.{send,retry,fail,latency}` with tags `profile`, `kind` (`send`/`wakeup`), and
`reason`/`outcome`. Not exposed on the API surface.

**The mapper is caller-supplied.** `WebhookResponse` is a pure data record with no reference
to the agent, so it cannot reach `agent.getTools().objectMapper()` on its own. Callers pass
the mapper explicitly — the recommended source is `agent.getTools().objectMapper()` for
agent-consistent Jackson configuration, but any locally-configured mapper works. Rejected
alternatives: (a) static `MAPPER` on `WebhookResponse` — bakes a Jackson configuration into
the public contract and makes `new ObjectMapper()` the implicit default, which is wrong for
any extension with timestamps or unknown-field tolerance; (b) `WebhookResponse` carrying a
deserializer SAM as a record component — expands the record signature publicly and shows up
in `equals` / `hashCode` / accessors.

**Deliberate omissions in V1** (candidates for V1.1 if customer feedback warrants):

- `as(TypeReference<T>, ObjectMapper)` for generic deserialization (`List<Foo>`, `Map<...>`).
  Workaround today: call `ObjectMapper#readValue` with the `TypeReference` directly against
  `response.body()`.
- `send(WebhookRequest)` builder-based overload — the four-way positional ladder covers the
  known use cases; promote to a builder only when it genuinely stops being enough.
- Non-JSON content types / non-POST methods.
- HMAC signing, idempotency keys, batching, async acks.
- SSRF-protection / URL allowlist.
- Automatic correlation ID / W3C traceparent injection (extensions set these themselves via
  the `headers` overloads).

## Relationship to agent-core

- `agent-core` depends on this module as `api(project(":extension-api"))` in its
  `build.gradle`.
- The implementation that *provides* this API to extensions (per-endpoint
  singletons, dispatcher mounting, property source) lives in
  `com.workato.agent.extension` inside `agent-core`. See
  `modules/agent-core/src/main/java/com/workato/agent/extension/CLAUDE.md`
  for the current implementation overview.
- No code outside `com.workato.agent.extension` in `agent-core` should reference
  types from this module directly — this module is for customer code and the
  single implementation seam.

## Relationship to legacy types in `com.workato.agent.config`

Two legacy types remain in `agent-core` under `com.workato.agent.config`:

- `ExtensionEndpoint` — `@Deprecated(forRemoval = true)` interface, non-functional
  (no bean implements it).
- `DefaultExtensionEndpoint` — the `workatoEndpoint` record customers currently
  autowire. Deprecated accessors emit a WARN on each call.

This module does **not** replace, re-export, or duplicate those types. They stay
in `agent-core` for backward compatibility with already-built customer JARs
that import them directly. New extensions should use `AgentExtensionV1`
instead — it carries the same data (name + YAML section) without the WARN noise.
Cross-extension visibility (`getAllConfigurations()`) was considered and
intentionally dropped before release; see the dedicated section above.

## Layout

```
modules/extension-api/
    build.gradle                                             (minimal, no agent deps)
    CLAUDE.md                                                (this file — maintainer view)
    README.md                                                (customer-facing API guide)
    PLAN.md                                                  (design plan for the webhook API work)
    src/main/java/
        com/workato/agent/extension/api/
            package-info.java                                (Javadoc marker)
            AgentExtensionV1.java                            (entry point)
            AgentExtensionContextV1.java                     (per-endpoint context + nested AgentConfiguration + ConnectionProperties)
            AgentWebhookV1.java                              (webhook transport + nested WebhookResponse / RetryPolicy / exceptions)
            AgentToolsV1.java                                (agent-provided utility bag — ObjectMapper for now)
    src/test/java/
        com/workato/agent/extension/api/
            AgentExtensionV1IntegrationTest.java            (full agent boot via testFixtures)
            AgentWebhookV1Test.java                         (unit tests for the webhook nested types)
            testext/
                AgentExtensionV1Controller.java             (probe controller for the test)
    src/test/resources/
        extensions/agent-extension-api-v1.yml                  (fixture: two profiles)
```
