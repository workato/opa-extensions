# ConfigurationSampleExtension

Minimal demonstration of the **cloud-managed extension model**. One
`@AgentConfigSupport`-annotated controller exposes two endpoints:

- `GET /ext/configuration_sample/{connectionId}/test` — connection health
  check: 200 when the cloud-pushed `timeout` is positive, 400 otherwise.
- `GET /ext/configuration_sample/{connectionId}/info` — returns the two
  parameter values plus **two parallel timestamps** showing the two
  complementary ways to detect a connection edit: `updatedAtByMyself`
  (tracked by the controller itself via the lifecycle-listener interface)
  and `updatedAtByApi` (read directly from the framework via
  `getConnectionCreatedAt()`).

## Features demonstrated

| Feature                                                         | How                                                                                                                                                                                                                                                                |
|-----------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@AgentConfigSupport(name = "configuration_sample")`            | Opts the controller into cloud-managed routing. The agent registers the class on first cloud push that carries `custom.configuration_sample.*`.                                                                                                                    |
| `@Value("${environment:unknown}") String` — raw-type static     | Resolved **once** at bean init from `agent-config-support.configuration_sample.environment`. Admin-pinned, same value for every connection.                                                                                                                        |
| `@Value("${timeout:0}") Supplier<Integer>` — connection-dynamic | The framework installs a lazy supplier that reads `getProfileConfiguration().get("timeout")` of the current request's connection on every `.get()` call. Default `0` applies when the key is absent.                                                               |
| `AgentConnectionLifecycleV1` — manual tracking                  | The controller implements the three callbacks to record a per-connection timestamp in a `ConcurrentHashMap<Long, Instant>`, surfaced in `/info` as `updatedAtByMyself`. Full control over the semantics.                                                           |
| `getConnectionCreatedAt()` — framework-provided tracking        | Also surfaced in `/info` as `updatedAtByApi`. The framework captures the instant on the add callback and does not refresh it on in-place config updates — only an edit (Workato performs Disconnect-then-Connect transparently) bumps the value. Zero author code. |
| Validation at the extension boundary                            | `/test` rejects the request with HTTP 400 when `timeout <= 0`. The Workato connector exposes this as the "Test connection" button; invalid connections never make it into recipes.                                                                                 |

## HTTP endpoints

### `GET /ext/configuration_sample/{connectionId}/info`

```json
{
  "environment": "prod",
  "timeout": 5000,
  "updatedAtByMyself": "2026-10-30T12:05:00Z",
  "updatedAtByApi": "2026-10-30T12:00:00Z"
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
`timeout > 0` validation.

For the general shape of a cloud-managed `connector.rb` (required DSL keys,
URL layout, `_connection_id`, `after_error_response` for error surfacing,
common pitfalls) see the top-level [README](../../../../../../README.md)
sections **Writing the Java extension — common rules** and **Step 3 —
Write the Ruby connector / Cloud-managed connector**.
