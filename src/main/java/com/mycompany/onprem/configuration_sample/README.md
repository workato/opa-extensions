# ConfigurationSampleExtension

Minimal demonstration of the **cloud-managed extension model**. One
`@AgentConfigSupport`-annotated controller exposes two endpoints:

- `GET /ext/configuration_sample/{connectionId}/test` — connection health
  check: 200 when the cloud-pushed `timeout` is positive, 400 otherwise.
- `GET /ext/configuration_sample/{connectionId}/info` — returns the two
  parameter values plus `connectionVersion` and the timestamp of the last
  cloud-reload callback for the connection.

## Features demonstrated

| Feature                                                         | How                                                                                                                                                                                                                            |
|-----------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@AgentConfigSupport(name = "configuration_sample")`            | Opts the controller into cloud-managed routing. The agent registers the class on first cloud push that carries `custom.configuration_sample.*`.                                                                                |
| `@Value("${environment:unknown}") String` — raw-type static     | Resolved **once** at bean init from `agent-config-support.configuration_sample.environment`. Admin-pinned, same value for every connection.                                                                                    |
| `@Value("${timeout:0}") Supplier<Integer>` — connection-dynamic | The framework installs a lazy supplier that reads `getProfileConfiguration().get("timeout")` of the current request's connection on every `.get()` call. Default `0` applies when the key is absent.                        |
| `AgentConnectionLifecycleV1` — minimal use                      | The controller implements the three callbacks only to record a per-connection `updatedAt` timestamp in a `ConcurrentHashMap<Long, Instant>`. The map is read back through `/info`.                                             |
| `getConnectionVersion()`                                        | Surfaced verbatim in the `/info` response. In production it increments only when the merged map actually changes between pushes — useful for lazy cache invalidation.                                                          |
| Validation at the extension boundary                            | `/test` rejects the request with HTTP 400 when `timeout <= 0`. The Workato connector exposes this as the "Test connection" button; invalid connections never make it into recipes.                                             |

## HTTP endpoints

### `GET /ext/configuration_sample/{connectionId}/info`

```json
{
  "environment": "prod",
  "timeout": 5000,
  "connectionVersion": 1,
  "updatedAt": "2026-10-29T12:00:00Z"
}
```

### `GET /ext/configuration_sample/{connectionId}/test`

Success (`timeout > 0`):

```
HTTP/1.1 200 OK
{"status":"ok","timeout":5000}
```

Failure (`timeout` missing or `<= 0`):

```
HTTP/1.1 400 Bad Request
{"status":"error","error":"timeout must be > 0","timeout":0}
```

## Configuration

### Static per-adapter — `agent-config-support.configuration_sample`

In the agent's `conf/config.yml`:

```yaml
agent-config-support:
  configuration_sample:
    environment: prod
```

The whole `agent-config-support:` section is **immutable from the cloud's
point of view**. Admins pin infra-level config here; the cloud cannot override
it silently.

### Per-connection — delivered from Workato

Admins do not write these fields directly. Each Workato connection is
delivered to the agent as a block under `custom.{adapter}.connections.{id}`,
keyed by the connection's numeric id (the same id that goes into the URL
segment the connector interpolates):

```yaml
custom:
  configuration_sample:
    connections:
      42:
        timeout: 5000
      43:
        timeout: 30000
```

Hot-reload: when the delivered set changes, `AgentConnectionLifecycleV1`
callbacks fire for added / updated / removed entries — your controller
instance keeps running, no restart needed.

### Opt-in

The class carries `@AgentConfigSupport(name = "configuration_sample")`. The
annotation name must match the Workato `CustomAdapter.name` and the YAML key
under `custom:` in cloud pushes.

## Workato connector

Companion Ruby connector:
[`src/main/ruby/configuration_sample/connector.rb`](../../../../../main/ruby/configuration_sample/connector.rb).
Declares `secure_tunnel: true`, exposes one action `get_info` that forwards
the `GET /ext/configuration_sample/{connection-id}/info` call. The `test:`
lambda hits `/test` so the "Test connection" button in the UI reflects the
`timeout > 0` validation. Workato's SDK runtime populates
`connection['_connection_id']` automatically for `secure_tunnel: true`
connectors — the lambda just interpolates it into the URL.

---

## Writing the `connector.rb`

The connector is the recipe-facing half of a cloud-managed extension. These
rules are not optional — getting any of them wrong manifests as a `404 Not
Found` or an unhelpful "Connection failed" with no further detail.

### Required top-level keys

```ruby
opa_extension_name = 'configuration_sample'

{
  title: "On-prem #{opa_extension_name}",
  secure_tunnel: true,                    # ← mandatory
  opa_extension_name: opa_extension_name, # ← mandatory, must match the Java @AgentConfigSupport(name=…)
  ...
}
```

- **`secure_tunnel: true`** marks the connector as agent-routed. Without it,
  Workato will try to call `localhost` directly from the cloud and fail.
- **`opa_extension_name:`** must match the name you give the Java class's
  `@AgentConfigSupport(name = "…")` annotation byte-for-byte, including
  case. A typo yields `404 Not Found` on every request with no helpful log.
  Keep the value in a local variable (`opa_extension_name = '…'`) and
  interpolate it everywhere else in the file — then there is a single
  source of truth for the name.
- Do **not** mix the old `agent_extension:` key with the new
  `opa_extension_name:` — only the latter is read.

### URL layout for `test:` / `execute:` / `webhook_*` lambdas

Every URL to the extension follows one shape:

```ruby
"http://localhost/ext/#{opa_extension_name}/#{connection['_connection_id']}/<your-path>"
```

- Workato populates `connection['_connection_id']` for `secure_tunnel: true`
  connectors — the lambda receives it in the `connection` hash. **Do not
  invent your own key** (`connection['id']`, `connection['conn_id']`, …) —
  the framework only sets `_connection_id`.
- The segment is required by the extension's URL mapping. If the
  interpolation ends up empty (nil `_connection_id`), the path collapses to
  `/ext/<name>//<path>` → normalised to `/ext/<name>/<path>` → `404`. If
  you see that symptom, double-check the connector is `secure_tunnel: true`
  and the `_connection_id` key is actually in the hash (log it from the
  lambda to be sure).
- Use `http://localhost` — not the recipe's base URL. Workato translates
  it into the agent-side tunnel URL automatically.

### Connection field names: avoid `profile`

A `profile` field in `connection.fields` makes Workato route the connector
through a different legacy pathway and your extension stops receiving
configuration. Pick anything else: `env`, `label`, `tenant`,
`instance_name` — all fine. Reserved: `profile`.

### Surface the extension's error message explicitly

The SDK's default behavior on a non-2xx response is to raise
`RequestFailedError` with just `"<code> <reason>"` and drop the response
body. The Workato UI then shows "Connection failed: 400 Bad Request" with
no clue what went wrong. To surface the extension's human message, attach
`after_error_response`:

```ruby
test: ->(connection) {
  get("http://localhost/ext/#{opa_extension_name}/#{connection['_connection_id']}/test")
    .after_error_response(/.*/) do |_code, body, _headers, message|
      payload = (parse_json(body) rescue nil) || {}
      error(payload['error'].presence || "#{message}: #{body}")
    end
},
```

For this to work, the Java side has to return a predictable JSON shape:

```java
return ResponseEntity.badRequest().body(Map.of(
    "status", "error",
    "error", "timeout must be > 0",
    "timeout", t));
```

The same pattern applies to `execute:` lambdas in actions.

### Output field labels get auto-titled

Workato UI generates human labels from field names by splitting camelCase
and title-casing — `connectionVersion` renders as "Connection version",
`updatedAt` as "Updated at". The underlying JSON keys don't change — only
the labels on the output pills. If the auto-title looks wrong, override
with explicit `label:`:

```ruby
output_fields: ->(_) {
  [
    { name: 'connectionVersion', label: 'Version', type: 'integer' },
    { name: 'updatedAt',         label: 'Last updated (UTC)' }
  ]
},
```

### Common `connector.rb` mistakes

- **Hard-coding the extension name all over the file** — easy to typo one
  of the copies. Keep `opa_extension_name = '…'` at the top, interpolate
  everywhere.
- **Forgetting `secure_tunnel: true`** — the connector loads, the UI
  renders the form, but every request goes to Workato cloud instead of the
  agent. Hard to debug because the symptom is "cannot reach localhost".
- **Dropping `connection['_connection_id']` from the URL** — common on
  copy-paste from an HTTP connector template. The path becomes
  `/ext/<name>/<endpoint>` and the extension returns `404`.
- **Letting the extension's 4xx body fall on the floor** — see the
  `after_error_response` note above. Users will tell you "the connection
  test is broken" without being able to say what the actual failure was.
- **Naming a field `profile`** — see above.

---

## Writing the Java extension

### Your controller is a singleton; guard mutable state yourself

One controller instance serves every connection of the adapter, from every
thread, for the whole life of the agent. Any plain instance field is
shared state. Keep per-connection data in a `ConcurrentHashMap<Long, …>`
keyed by `connectionId`, keep per-request data in local variables inside
the handler, and do not touch raw instance fields without one of those
scopes.

```java
private final ConcurrentHashMap<Long, Instant> updatedAt = new ConcurrentHashMap<>();

@Override
public void onConnectionUpdated(long connectionId, AgentConfiguration cfg) {
    updatedAt.put(connectionId, Instant.now());
}
```

A `private Instant updatedAt` written from `onConnectionUpdated` would make
every connection's `/info` return whichever connection last updated — subtle,
deadly under load.

### Lifecycle callbacks run outside any HTTP request

`onConnectionAdded` / `onConnectionUpdated` / `onConnectionRemoved` fire
when the cloud configuration for the adapter changes. They do not run on an
HTTP request thread, so every request-scoped method on
`AgentExtensionContextV1` returns a sentinel inside a callback:

- `getConnectionId()` → `-1L`
- `getProfileConfiguration()` → only the static per-adapter keys
- `getRequestMetadata()` → `{}`
- `getConnectionVersion()` → `0L`

Use the arguments the framework hands you — `connectionId`, `mergedConfig`,
`newMergedConfig` — instead of reaching into the context. Exceptions thrown
from a callback are logged and ignored; don't rely on them for anything
load-bearing.

### `connectionVersion` is a soft hint, not a system of record

- Monotonic within one agent lifetime.
- Starts at `1` the first time the framework reports the connection,
  increments on each change to the merged config.
- Resets to `1` after a `onConnectionRemoved` → `onConnectionAdded` pair,
  which happens on genuine deletion of the connection, user-triggered
  Disconnect, and the first configuration delivery after agent restart.

Treat it as the version key of a `ConcurrentHashMap<Long, Cached>` for lazy
cache invalidation. Don't persist it, don't compare it across agent
restarts, don't log it as authoritative state.

### Return 4xx with a parseable JSON body

The companion connector relies on an `after_error_response` hook to pull a
human message out of your 4xx body — so give it one. A consistent shape
across every error endpoint also makes action-level `execute:` errors
usable:

```java
return ResponseEntity.badRequest().body(Map.of(
    "status", "error",
    "error", "timeout must be > 0",   // human message, surfaced in the UI
    "timeout", t));                   // raw value for debugging
```

If you return `ResponseEntity.badRequest().build()` (no body) the UI falls
back to a bare "400 Bad Request" and your users won't know what went wrong.

### Deploying the JAR

Dropping a new JAR into the agent's `lib_ext/` directory is not enough —
restart the agent so it picks the class up. The name you pass to
`@AgentConfigSupport(name = "…")` must match `opa_extension_name` in
`connector.rb` byte-for-byte; a typo yields a bare `404 Not Found` with no
helpful log line. The agent's `/health` endpoint shows every registered
extension — yours should appear there with `status: "enabled"` before you
expect any request to work.
