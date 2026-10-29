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
| `@Value("${timeout:0}") Supplier<Integer>` — connection-dynamic | The agent's `ValueSupplierBeanPostProcessor` installs a lazy supplier that reads `getProfileConfiguration().get("timeout")` of the current request's connection on every `.get()`. Default `0` applies when the key is absent. |
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

### Per-connection — delivered from the Workato monolith

Admins do not write these fields directly. For each `SharedAccount` bound to
the agent group the monolith encrypts the fields and pushes them through
`/config`. The resulting (encrypted) YAML the agent receives looks like:

```yaml
custom:
  configuration_sample:
    connections:
      42:
        timeout: 5000
      43:
        timeout: 30000
```

Hot-reload: a subsequent push with a different connection set is diffed
against the in-memory registry — `AgentConnectionLifecycleV1` callbacks fire
for added / updated / removed entries, and the Spring context stays alive.

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
`timeout > 0` validation. In production the Workato SDK framework sets
`connection['_connection_id']` to the `SharedAccount#id` automatically for
every `secure_tunnel: true` connector.
