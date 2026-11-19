# Agent Extensions API — Customer Guide

> If you are reading this to **write an extension** for the Workato On-Premise
> Agent, you are in the right place. If you are reading this to **modify the
> API itself**, see `CLAUDE.md` in this directory instead.

Official product docs: <https://docs.workato.com/en/on-prem/agents/extension>

## What is an agent extension?

An extension is a Java JAR dropped into the agent's `lib_ext/` directory. It
contains a Spring `@RestController` that the agent mounts under
`/ext/{profile-name}/*`. Each extension gets its own isolated Spring context
inside the agent, so your code runs with its own beans and its own classloader
while still having read access to the agent's internal services through
autowiring.

The agent calls your controller when a Workato recipe sends a request to the
extension endpoint; your controller does whatever work it needs (talk to an
internal system, transform data, call a database) and returns a response.

## Quick start

### 1. Add the API as a provided-scope dependency

Gradle:

```gradle
dependencies {
    compileOnly("com.workato.build:extension-api")  // use the matching agent version
}
```

The API is **compile-only / provided** — do not shade it into your JAR. The
agent provides it at runtime; shading would cause classloader conflicts.

### 2. Write the controller

```java
package com.example.my_extension;

import com.workato.agent.extension.api.AgentExtensionApiV1;
import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/my-operation")
public class MyController {

    @Autowired
    private AgentExtensionApiV1 agent;

    @Value("${my-custom-property}")              // resolved from YAML; see §4
    private String myProperty;

    @PostMapping
    public Map<String, Object> handle(@RequestBody Map<String, Object> payload) {
        AgentConfiguration cfg = agent.getContext().getProfileConfiguration();
        // ... business logic ...
        return Map.of("profile", agent.getContext().getProfileName(),
                "received", payload);
    }
}
```

### 3. Declare the extension in `config.yml`

```yaml
extensions:
  my-extension:
    controller: com.example.my_extension.MyController
    my-custom-property: value-visible-via-@Value
    other-prop: 42
```

### 4. Deploy

Build your JAR, copy it into `WORKATO_AGENT_HOME/lib_ext/`, restart the agent.
Requests from Workato arrive at `POST /ext/my-extension/my-operation`.

> **Restart required to add, remove, or load code for extensions.** Dropping a
> new JAR into `lib_ext/` or adding a new entry under `extensions:` does not
> take effect until the agent is restarted; routes and classloaders are fixed
> for the process lifetime.
>
> **Reloading `config.yml` updates values only.** For an already-registered
> extension, edits to its YAML values are picked up on the next request via
> `@Value("${...}")`. The map returned by
> `AgentExtensionContextV1.getProfileConfiguration()` is a snapshot captured at
> startup and does **not** change — prefer `@Value` for keys that may be edited
> at runtime.

## API Reference

Every type lives in `com.workato.agent.extension.api`.

### `AgentExtensionApiV1` — the autowired bean

```java
public interface AgentExtensionApiV1 {
    AgentExtensionContextV1 getContext();
}
```

Autowire this. One instance exists per extension endpoint; it is scoped to
your extension's isolated Spring context. The bean name is `"agentExtensionApi"`,
but you should autowire by type, not by name.

### `AgentExtensionContextV1` — your endpoint's view of the agent

```java
public interface AgentExtensionContextV1 {
    String getProfileName();

    AgentConfiguration getProfileConfiguration();
}
```

| Method                      | What you get                                                                                                                                                                       |
|-----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `getProfileName()`          | The YAML key your extension is declared under (`my-extension` in the example above). Stable for the lifetime of the agent.                                                         |
| `getProfileConfiguration()` | Your extension's YAML section as a map, including the `controller` / `controllerClass` key alongside your custom properties. Snapshot — does not change across agent YAML reloads. |

> **Each extension sees only its own profile.** There is no API for discovering
> other extensions' configuration. (Note that this does not provide full
> isolation on its own: an extension controller still shares a Spring parent
> context with agent internals and can reach other beans by autowiring.)

### `AgentWebhookApiV1` — outbound HTTP transport

Obtained via `agent.getWebhookApi()`. A thin wrapper over `POST` with automatic retry and
exponential backoff — the only outbound-HTTP primitive extensions need when they want to push an
event to a recipe webhook URL or wake a pull-mode trigger. All nested types
(`WebhookResponse`, `RetryPolicy`, `WebhookDeliveryException`, `WebhookResponseException`) live
on the interface itself, so a single import brings in the whole vocabulary:

```java
import com.workato.agent.extension.api.AgentWebhookApiV1;
import com.workato.agent.extension.api.AgentWebhookApiV1.WebhookResponse;
import com.workato.agent.extension.api.AgentWebhookApiV1.RetryPolicy;
```

**`send` ladder** — pick the shortest overload that carries exactly what you need:

| Overload                           | When to use                                                        |
|------------------------------------|--------------------------------------------------------------------|
| `send(url, body)`                  | Simplest — POST JSON, profile's effective retry policy, no headers |
| `send(url, body, headers)`         | Add correlation IDs, auth tokens, custom trace headers             |
| `send(url, body, policy)`          | Override retry policy per call (tighter timeout, more attempts)    |
| `send(url, body, policy, headers)` | Both policy and headers                                            |

**`wakeup` ladder** — same four shapes, minus the body parameter. Sends an empty POST
(`Content-Length: 0`, not `{}`) for pull-mode triggers that fetch records through a separate
endpoint:

```java
agent.getWebhookApi().

wakeup("https://recipe/trigger");
agent.

getWebhookApi().

wakeup("https://recipe/trigger",
       Map.of("X-Request-ID", "req-1"));
```

**Response & deserialization.** Every call returns a `WebhookResponse`:

```java
WebhookResponse resp = agent.getWebhookApi().send(url, payload);
if(resp.

isSuccessful()){
Ack ack = resp.as(Ack.class);     // JSON → POJO/record via a shared Jackson ObjectMapper
}
```

`WebhookResponse.as(Class<T>)` throws `WebhookResponseException` on `null` body, invalid JSON,
or type mismatch. For `List<Foo>` / `Map<String, Bar>` generics, call `.body()` and parse the
string yourself (a `TypeReference` overload is on the V1.1 shortlist).

**Delivery semantics.**

- **At-least-once** — the agent may deliver the same request more than once if the recipe
  answered with a retryable status (`5xx` / `408` / `429` by default) or a network error.
- **Synchronous** — the calling thread blocks for the whole retry chain.
- **Only `application/json`** — the agent sets `Content-Type: application/json` for `send` and
  omits it for `wakeup`; any such header you pass in `headers` is ignored.
- **Exhausted → `WebhookDeliveryException`.** Non-retryable non-2xx is **returned** as a
  `WebhookResponse` for your code to decide — only exhaustion or a non-retryable network error
  raises an exception.

**Configuration via YAML.** The retry defaults for each profile come from an optional
`webhook:` section:

```yaml
extensions:
  my-extension:
    controller: com.example.MyController
    webhook:
      retries: 10              # default 5
      initial-delay-ms: 500    # default 1000
      multiplier: 2.0          # default 2.0
      max-delay-ms: 60000      # default 30000
      jitter-factor: 0.2       # default 0.0, max 1.0
      timeout-ms: 10000        # default 30000
      response-cap-bytes: 524288  # default 1048576 (1 MB)
```

Everything is optional — a profile without a `webhook:` section still gets sensible defaults
(5 attempts, 1s → 2s → 4s → 8s backoff, no jitter, 30s timeout, 1 MB response cap). Invalid
values (`retries: -1`, `multiplier: 0.5`) log a WARN and fall back to the default **for that
specific key** — a typo must not keep the extension from loading.

#### subscribe/unsubscribe pattern

The webhook API does not include lifecycle management for subscriptions. Build it on top of
your own `@RestController` plus the webhook transport. The contract below is the one the
Workato SDK's `webhook_subscribe` / `webhook_unsubscribe` lambdas use for a webhook-proxy
connector — any extension that wants to serve as such a connector follows it verbatim.

> ⚠ **The subscribe/unsubscribe pattern below is single-node only — treat it as legacy.**
> OPA agents commonly run as a group (several instances behind a load balancer). The
> `/subscribe` call that Workato routes to one agent registers the subscription in that
> agent's in-memory map; the other agents in the group know nothing about it. The next event
> picked up by a different agent is dropped on the floor; the SDK's `unsubscribe` is also
> routed to a single arbitrary node and leaves orphan entries on the others. Making this work
> under a group requires either a shared registry (database, Redis, …) or a group-aware
> subscribe fan-out — both out of scope for this API and currently unaddressed by the SDK.
>
> **The outbound API itself — {@link AgentWebhookApiV1#send send} and
> {@link AgentWebhookApiV1#wakeup wakeup} — has no such limitation.** It POSTs to a URL
> Workato controls; the agent is just an HTTP client. Any agent in the group that holds the
> URL can deliver, and nothing in the webhook transport cares about group membership. If your
> extension stores subscription state out-of-band (e.g., pulled from an external system on
> each event), the webhook API alone is enough and the pattern below is not needed.

**The contract (from the SDK side):**

| Direction                         | Endpoint                                  | Body                                                                         |
|-----------------------------------|-------------------------------------------|------------------------------------------------------------------------------|
| Workato → extension (subscribe)   | `POST /webhook/subscribe`                 | `{"workato_webhook_url": "...", "recipe_id": "...", "connection_id": "..."}` |
| Workato → extension (unsubscribe) | `POST /webhook/unsubscribe`               | `{"recipe_id": "...", "connection_id": "..."}`                               |
| extension → Workato (event)       | `POST {workato_webhook_url}` with payload | `X-Workato-Event-Id` header carries a dedup id                               |

> **The webhook URL is per-run; identity is whatever the extension chooses.** Workato mints a
> fresh `workato_webhook_url` every time a recipe (re)starts — so the URL is the *address* you
> POST to, not the *key* you look up by. The SDK's unsubscribe body is
> `{"recipe_id": …, "connection_id": …}` and no URL is passed. **The `recipe_id` field, however,
> is not necessarily the Workato recipe id — the SDK fills it in as**
> `webhook_subscribe_output['id'] || webhook_subscribe_output['recipe_id']`: if the extension
> returned `{"id": "<some-uuid>"}` from `/subscribe`, that same string arrives in `recipe_id` on
> `/unsubscribe`. That lets the extension pick its own identity — the recommended
> approach — instead of relying on `connection_id` or the real recipe id.
>
> Possible keys for the registry, from least to most flexible:
>
> | Key                                        | When to use                                                                 |
> |--------------------------------------------|-----------------------------------------------------------------------------|
> | `(connection_id, recipe_id)`               | Multiple recipes may share one connection — the usual case                  |
> | **Extension-generated UUID** (recommended) | Full control over identity; survives SDK quirks and extension-side rekeying |
>
> A `connection_id`-only key is not safe: nothing prevents several recipes from sharing one
> connection, so the second subscribe would overwrite the first.

```java

@RestController
@RequestMapping("/webhook")
public class WebhookSubscriptionController {

    @Autowired
    private AgentExtensionApiV1 agent;

    /** Keyed by an extension-generated UUID returned to the SDK as the subscribe response id. */
    private final Map<String, Subscription> active = new ConcurrentHashMap<>();

    /** SDK bodies — field names must match the Workato SDK JSON. */
    public record SubscribeRequest(
            @JsonProperty("workato_webhook_url") String workatoWebhookUrl,
            @JsonProperty("recipe_id") String recipeId,
            @JsonProperty("connection_id") String connectionId) {
    }

    public record UnsubscribeRequest(
            // Carries whatever the extension returned under `id` at subscribe time
            // (SDK fallback: `webhook_subscribe_output['id'] || webhook_subscribe_output['recipe_id']`).
            @JsonProperty("recipe_id") String subscriptionId,
            @JsonProperty("connection_id") String connectionId) {
    }

    /** Per-subscription state the extension owns. */
    private record Subscription(String webhookUrl, String workatoRecipeId, String connectionId,
            /* extension-defined routing state: topic, filter, auth, … */ Object state) {
    }

    @PostMapping("/subscribe")
    public Map<String, Object> subscribe(@RequestBody SubscribeRequest req) {
        String subscriptionId = UUID.randomUUID().toString();
        active.put(subscriptionId,
                new Subscription(req.workatoWebhookUrl(), req.recipeId(), req.connectionId(), /* state */ null));
        // The SDK echoes this `id` back as `recipe_id` on unsubscribe — see the SDK fallback above.
        return Map.of("id", subscriptionId);
    }

    @PostMapping("/unsubscribe")
    public void unsubscribe(@RequestBody UnsubscribeRequest req) {
        active.remove(req.subscriptionId());
    }

    // Called from the extension's own event source (polling loop, external bus listener, …).
    // The dedup header lets the Workato trigger drop duplicates on its side.
    private void dispatch(String subscriptionId, Object payload) {
        Subscription sub = active.get(subscriptionId);
        if (sub == null)
            return;
        WebhookResponse r = agent.getWebhookApi().send(sub.webhookUrl(), payload,
                Map.of("X-Workato-Event-Id", UUID.randomUUID().toString()));
        log.debug("Delivered to {} (sub {}, recipe {}), status {}",
                sub.webhookUrl(), subscriptionId, sub.workatoRecipeId(), r.statusCode());
    }
}
```

### `AgentExtensionContextV1.AgentConfiguration` — map view of a YAML section

Nested on `AgentExtensionContextV1`, so one `AgentExtensionContextV1` import is enough — the
configuration type comes with it.

```java
public interface AgentExtensionContextV1 {
    // ...
    interface AgentConfiguration extends Map<String, Object> {
    }
}
```

Deliberately a bare `Map<String, Object>` extension. Use the standard JDK map
API: `cfg.get("key")`, `cfg.containsKey(...)`, iteration, streams. YAML nested
sections arrive as nested `Map` values; YAML lists arrive as `List` values.

> The instance you receive is an **unmodifiable view backed by a defensive copy**
> — any `put` / `remove` / `clear` / `putAll` throws
> `UnsupportedOperationException`, and the agent's internal state is unaffected.
> Treat it as read-only.

## Property injection with `@Value`

Any key under your extension's YAML section is injectable via Spring's
`@Value("${key}")`:

```yaml
extensions:
  my-extension:
    controller: com.example.my_extension.MyController
    api.endpoint: https://internal.example.com
    api.timeout-ms: 5000
```

```java

@Value("${api.endpoint}")
private String endpoint;

@Value("${api.timeout-ms:3000}")   // defaults supported
private int timeoutMs;
```

Dots in YAML keys are treated as literal characters, not nesting separators —
the lookup is a direct map `get()`. If you have a nested YAML section and want
to inject individual leaves, flatten the keys or inject the parent `Map`.

## Migrating from the legacy API

Older agent versions exposed two types in `com.workato.agent.config`. Both are
`@Deprecated(forRemoval = true)` and will be removed in a future major release;
every call to a deprecated method emits a `WARN` in the agent's log pointing at
this API.

### `DefaultExtensionEndpoint` (bean `"workatoEndpoint"`) — still works, now noisy

Record historically autowired as:

```java

@Autowired
DefaultExtensionEndpoint workatoEndpoint;
```

Replace with:

```java

@Autowired
AgentExtensionApiV1 agent;
```

Then migrate each call site:

| Legacy call                       | Replacement                                    |
|-----------------------------------|------------------------------------------------|
| `workatoEndpoint.name()`          | `agent.getContext().getProfileName()`          |
| `workatoEndpoint.configuration()` | `agent.getContext().getProfileConfiguration()` |

The returned `AgentConfiguration` is a `Map<String, Object>`, so if your old
code called `.get("key")` on the legacy `Configuration`, the new call is
identical. Typed accessors from the legacy `Configuration` (e.g., coerce to
`int`/`boolean`) are not currently on `AgentConfiguration` — do the coercion
yourself, or inject the leaf with `@Value`.

### `ExtensionEndpoint` interface — not supported

The deprecated `com.workato.agent.config.ExtensionEndpoint` interface has no
bean registered against it — `@Autowired ExtensionEndpoint` fails at startup
with `NoSuchBeanDefinitionException`. Use `@Autowired AgentExtensionApiV1`
instead.

### `@Value` continues to work

The `@Value("${property}")` path is unchanged — resolution still goes through
the agent's internal `EndpointPropertySource` and does **not** emit any WARN.
If your extension only reads config properties via `@Value`, you have nothing
to migrate right now. Still, moving to `AgentExtensionApiV1` is recommended
before the legacy types are removed.

## Troubleshooting

| Symptom                                                                | Likely cause                                                                                                                                                                             |
|------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `NoSuchBeanDefinitionException` on `@Autowired ExtensionEndpoint`      | You are using the deprecated interface. Switch to `AgentExtensionApiV1`. See migration section.                                                                                          |
| WARN `Extension [X] called deprecated DefaultExtensionEndpoint#name()` | A caller is still on the legacy record. Follow the migration table — the WARN mentions the exact replacement.                                                                            |
| `/ext/{name}/{path}` returns 404                                       | Either the extension name is not declared under `extensions:` in `config.yml`, or the controller class could not be loaded (check the agent log for `Endpoint [{name}] failed to load`). |
| Changes to a redeployed JAR don't take effect                          | Classloader is fixed for the agent's lifetime. Restart the agent after replacing a JAR.                                                                                                  |
| Request parameter `@PathVariable id` is not bound                      | Build your JAR with the `-parameters` compiler flag, or declare the name explicitly: `@PathVariable("id")`.                                                                              |

## Versioning

The API is named `AgentExtensionApiV1` / `AgentExtensionContextV1` (with its nested
`AgentConfiguration`) / `AgentWebhookApiV1` (with its nested `WebhookResponse`, `RetryPolicy`,
`WebhookDeliveryException`, `WebhookResponseException`).

- `AgentExtensionApiV1` and `AgentExtensionContextV1` are **consumer-only** —
  customer code calls their methods but does not implement them (the
  implementation is supplied by the agent). Methods may be added to these
  interfaces without breaking customer compile or runtime, as long as the
  customer does not provide their own implementation.
- `AgentConfiguration` extends `Map<String, Object>`; additional typed
  accessors may be added here in future iterations, source- and binary-compatible
  with existing calls.
- Incompatible changes will introduce a parallel `V2` family and keep `V1`
  running until a documented removal window.

If you write a hand-rolled implementation of `AgentExtensionApiV1` or
`AgentExtensionContextV1` for testing, prefer the harness in `extension-test`
over implementing the interfaces directly — the harness is kept in sync as
the interfaces evolve.
