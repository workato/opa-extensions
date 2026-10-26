# SubscriptionsSampleExtension

Demonstration of the **cloud-managed extension model** and the **Subscription
API** — `AgentConnectionLifecycleV1`. One singleton controller serves many
connections, learns about connections appearing / changing / disappearing
through callbacks the agent fires from its cloud-reload thread, and surfaces
everything it has observed through a single read-only operation.

The extension exists purely to make three things visible side-by-side in one
HTTP response:

1. **Static per-adapter config** the admin pinned in `config.yml` under
   `agent-config-support.subscriptions_sample.*` (same value for every connection,
   resolved once at bean init).
2. **Connection-dynamic config** the Workato monolith pushed under
   `custom.subscriptions_sample.connections.{id}.*` (different for each connection,
   resolved per request against whichever connection the URL addresses).
3. **Lifecycle statistics** the controller accumulated as the cloud push
   added / updated / removed connections — the "subscription" to a stream of
   connection events the `AgentConnectionLifecycleV1` interface gives you.

If you are reading this to learn how a cloud-managed extension differs from a
legacy local one, this is the shortest end-to-end example in the repo.

## Extension API features demonstrated

| Feature                                                               | How                                                                                                                                                                                                                                                                                                           |
|-----------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@AgentConfigSupport(name = "subscriptions_sample")`                         | Opts the controller into cloud-managed routing. The adapter name is baked into the annotation (not into `config.yml`); the agent registers the class on first cloud push that carries `custom.subscriptions_sample.*`.                                                                                               |
| **`AgentConnectionLifecycleV1`** (`onConnectionAdded/Updated/Removed`) | The whole point of the demo. Framework calls the three default-overridable methods from its cloud-reload thread whenever the next cloud push mutates the adapter's connection set. The controller keeps counters and per-connection history that the operation surfaces back.                               |
| URL layout `/ext/{adapter}/{connection-id}/*`                         | Second path segment is `SharedAccount#id` as a `long`. The controller takes it as `@PathVariable("connId") long` and also reads it reflectively via `getConnectionId()` — both paths line up in the response.                                                                                                 |
| `getProfileConfiguration()` as **request-scoped merged view**          | Returns `static per-adapter ∪ custom.{adapter}.connections.{conn-id-of-current-request}`. Static wins on key collision (admin pin). The operation iterates `keySet()` and shows the result under `connection.allKeys`.                                                                                        |
| `@Value("${…}") T` — raw-type, static per-adapter                     | `environment`, `region`, `bannerText` fields on the controller resolve **once** at bean init from `agent-config-support.subscriptions_sample.*`. Changing the key in a cloud push would **not** be seen through these fields — they are the right choice for admin-pinned infra and the wrong one for connection data. |
| `@Value("${…}") Supplier<T>` — connection-dynamic                      | `label` field is `Supplier<String>`. The agent's `ValueSupplierBeanPostProcessor` intercepts the injection and installs a lazy supplier that reads `getProfileConfiguration().get("label")` of **the current request's connection** on every `.get()`. Default `${label:unlabeled}` applies when the key is absent. |
| `getConnectionId()` / `getConnectionVersion()`                        | Both surfaced verbatim in the response so a caller can see the request-scoped values the agent provides to cloud-managed controllers. `getConnectionVersion()` increments only when the merged map actually changes between pushes — useful for lazy cache invalidation (not demonstrated beyond readback here). |
| Thread-safe controller state (singleton + concurrent requests)         | `AtomicLong` counters + `ConcurrentHashMap<Long, ConnectionStats>` cover the two directions of concurrency: callbacks running on the cloud-reload thread parallel to request-worker threads, and multiple request workers hitting the shared singleton at once.                                              |
| Immutable per-connection snapshot pattern                             | `ConnectionStats` is a `record` with `withBumpedUpdate(...)` that replaces instead of mutating — trivial to use inside `ConcurrentHashMap#compute` without extra locking.                                                                                                                                     |

Not demonstrated here (see other extensions):

- Autowiring `AgentExtensionApiV1` via `@Inject` / `@Autowired` and reading one
  key out of a legacy `extensions:` section → **SecurityExtension**.
- `@Value("${…}")` on **raw** types in a legacy extension (no cloud-managed
  dual-mode) → **NtlmExtension**.
- Outbound HTTP via `AgentWebhookApiV1.send(...)` with per-profile retry →
  **TimerExtension**.
- Background scheduler + `DisposableBean.destroy()` lifecycle →
  **TimerExtension**.

## HTTP endpoint

```
GET /ext/subscriptions_sample/{connectionId}/inspect
```

Response:

```json
{
  "adapter": "subscriptions_sample",
  "connectionId": 42,
  "connectionIdFromPath": 42,
  "connectionVersion": 1,
  "staticPerAdapter": {
    "environment": "prod",
    "region": "us-east-1",
    "bannerText": "subscriptions_sample-demo"
  },
  "connection": {
    "label-via-supplier": "primary",
    "label-via-config": "primary",
    "allKeys": ["host", "label", "port"]
  },
  "lifecycle": {
    "addedTotal": 1,
    "updatedTotal": 0,
    "removedTotal": 0,
    "trackedConnections": [42],
    "thisConnection": {
      "firstSeenAt": "2026-10-26T12:00:00Z",
      "lastSeenAt":  "2026-10-26T12:00:00Z",
      "updateCount": 0,
      "lastKnownKeys": ["host", "label", "port"]
    }
  }
}
```

`connectionIdFromPath` is the `@PathVariable` the agent extracted from the URL;
`connectionId` is `getConnectionId()` on the request-scoped context. They
should always match in production — the response exposes both so a mismatch
would be visible immediately.

## Configuration

Cloud-managed extensions are configured in **two places** — unlike legacy
`extensions:` extensions which have one YAML section each:

1. **Static per-adapter** (admin-pinned, lives in the agent's `config.yml`).
2. **Per-connection** (business-level, delivered through the cloud push from
   the Workato monolith).

### Install the JAR

Same as every other extension in this repo — build the fat jar, drop it in the
agent's extension classpath directory. See the top-level
[README](../../../../../../README.md#installing-the-extension-to-opa) for the
exact steps.

### Static per-adapter — `agent-config-support.subscriptions_sample`

This block in the agent's `conf/config.yml` is the one admins touch:

```yaml
agent-config-support:
  subscriptions_sample:
    environment: prod
    region: us-east-1
    banner-text: subscriptions_sample-demo
```

- The whole `agent-config-support:` section is **immutable from the cloud's
  point of view** — no cloud push can override it. This is by design: admins
  pin infra-level config here (`proxy-url`, `tls-ca-path`, environment markers)
  and expect the cloud to be unable to change it silently.
- Keys here are also merged into every connection's `getProfileConfiguration()`
  view, so the operation can read them via `getProfileConfiguration().get(...)`
  the same way it reads cloud-pushed keys. On a collision the **static value
  wins** (admin pin-semantics).
- `environment`, `region`, `banner-text` are the three static keys this demo
  reads via raw-type `@Value` on the controller. Omitting any of them is fine
  — the controller falls back to the `${key:default}` on the field.

> **There is no `extensions.subscriptions_sample:` block.** Cloud-managed extensions
> deliberately do **not** appear under `extensions:` — the agent locates the
> class via the `@AgentConfigSupport(name = "subscriptions_sample")` annotation at
> startup and mounts it under `/ext/subscriptions_sample/{connection-id}/*` only after
> the first cloud push with `custom.subscriptions_sample.*` arrives.

### Per-connection — delivered from the Workato monolith

Admins **do not write these fields directly**. The flow is:

1. In Workato, create a `CustomAdapter` whose name matches the annotation
   (`subscriptions_sample`), with a `connector.rb` that declares `secure_tunnel: true`.
2. For each connection a recipe wants to use, create a `SharedAccount` and
   bind it to the on-prem agent group. Each `SharedAccount#id` becomes the
   `{connection-id}` segment of the URL.
3. The monolith's `OnPrem::ManagedConfigBuilder` collects every active
   `SharedAccount` for the group, encrypts the fields, and `POST`s them to
   each agent's `/config` endpoint. The agent decrypts and the extension's
   `AgentConnectionLifecycleV1.onConnectionAdded(id, mergedConfig)` fires for
   each new connection.

The resulting (encrypted) YAML the agent receives from the cloud looks like:

```yaml
custom:
  subscriptions_sample:
    connections:
      42:
        label: primary
        host: prod.example.com
        port: 2242
      43:
        label: staging
        host: staging.example.com
        port: 2243
```

Hot-reload: a subsequent push with a different set of connections is diffed
against the in-memory registry — the three
`AgentConnectionLifecycleV1` callbacks fire for added / changed / removed
entries, and request-handlers for the untouched connections keep running
without any disruption (Spring context is **not** torn down).

### Opt-in rules

- The extension's class **must** carry `@AgentConfigSupport(name = "…")` for
  the agent to pick it up as cloud-managed. Without the annotation the JAR is
  loaded (via the shared `lib_ext/` classloader) but the controller is never
  mounted — the extension is "dark".
- The annotation name has to match the Workato `CustomAdapter.name` **and**
  the YAML key under `custom:` in cloud pushes. All three live in sync by
  convention; the agent rejects a name starting with `workato.` or equal to
  `overrides` (reserved namespace, see
  `AgentConfigSupportReservedNames`).
- Admins who need to promote a legacy JAR without rebuilding it can add it
  by FQCN under `agent-config-support.overrides` in the agent's `config.yml`
  — this is the escape hatch for JARs whose author cannot ship
  `@AgentConfigSupport` today. Not needed here (this JAR already carries
  the annotation).

### What the operation sees under different configurations

| Scenario                                                    | `staticPerAdapter.environment` | `connection.label-via-supplier` | `lifecycle.addedTotal` |
|-------------------------------------------------------------|--------------------------------|---------------------------------|------------------------|
| Fresh agent, static set, one connection pushed              | from YAML                      | from connection                 | `1`                    |
| No `agent-config-support:` block in `config.yml`            | `"unknown"` (`@Value` default) | from connection                 | `1`                    |
| Connection in URL has no `label` key                        | from YAML                      | `"unlabeled"` (default)         | `1`                    |
| URL addresses a connection id not present in the last push  | from YAML                      | `"unlabeled"` (fallback)        | varies                 |
| After the admin edits YAML and restarts the agent           | reflects edit                  | from connection                 | reset to count of push |
| After the monolith pushes a different `label` for conn `42` | from YAML                      | **reflects push on next req**   | unchanged              |

The last two rows together are the whole mental model: static takes effect
only on process restart (immutable from cloud), connection values take effect
on the next request after the push (hot-reload via in-place map update).

## Workato connector

Companion Ruby connector:
[`src/main/ruby/subscriptions_sample/connector.rb`](../../../../../main/ruby/subscriptions_sample/connector.rb).
Declares `secure_tunnel: true`, exposes one action `inspect` that forwards the
`GET /ext/subscriptions_sample/{connection-id}/inspect` call and returns the extension's
JSON envelope verbatim. In production the Workato SDK framework sets
`connection['_connection_id']` to the `SharedAccount#id` automatically for every
`secure_tunnel: true` connector — the connector interpolates that into the URL.
