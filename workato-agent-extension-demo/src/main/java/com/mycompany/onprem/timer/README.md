# TimerExtension

Periodic webhook emitter. A Workato recipe subscribes with
`{message, period_seconds}`; the extension schedules a task that pushes
`{message, generated_at, tick, subscription_id, period_seconds}` back to the
recipe every N seconds through `AgentWebhookApiV1.send(...)`.

Exists purely to showcase the webhook half of the Extension API — the
`AgentWebhookApiV1` interface that was added in 34.0, together with the SDK's
`webhook_subscribe` / `webhook_notification` / `webhook_unsubscribe` trigger
contract.

> ⚠ **The subscribe/unsubscribe part of this extension is demonstrative and
> considered legacy** — treat it as a reference for how the SDK contract
> looks on the agent side, not as a template to copy into production.
>
> Subscription state lives in a single `ConcurrentHashMap` inside **one**
> agent process. OPA agents commonly run as a group (several instances behind
> a load balancer). Workato routes the `webhook_subscribe` call to one
> arbitrary member of the group — only that one agent knows the subscription
> exists. If a scheduled tick, an inbound event, or the matching
> `webhook_unsubscribe` later lands on a different member, it is dropped on
> the floor. That directly contradicts the whole point of running agents as
> a group (fault-tolerance + horizontal scale), so this pattern is not
> acceptable for production without a shared registry (database, Redis, …)
> or a group-aware subscribe fan-out — both out of scope for this API and
> currently unaddressed by the SDK. See
> `extension-api/README-extension-api.md` → "subscribe/unsubscribe pattern"
> for the authoritative statement of this limitation.
>
> The **outbound** half — `AgentWebhookApiV1.send(...)` and its retry /
> dedup machinery — does **not** have this limitation. Any agent in the
> group that holds the webhook URL can deliver, and the API itself does not
> care about group membership. If your extension sources subscription state
> from an external system (polled per tick, pushed by that system, …), the
> outbound half alone is enough and the subscribe/unsubscribe lambdas in
> this demo are not needed.

## Extension API features demonstrated

| Feature                                             | How                                                                                                                                                                                                                                                                                                          |
|-----------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`AgentWebhookApiV1.send(url, body, headers)`**    | The whole point of the demo. One call per tick — delivery, retries, and backoff are the API's job.                                                                                                                                                                                                           |
| Automatic retries + exponential backoff             | Configured per-profile under `extensions.timer.webhook:` in YAML (see **Configuration** below). We do not implement retry ourselves.                                                                                                                                                                         |
| Dedup header pattern                                | Every outbound call carries a fresh `X-Workato-Event-Id: <uuid>` — Workato uses it to drop duplicates when at-least-once delivery doubles up a tick.                                                                                                                                                         |
| Jackson records with `@JsonProperty("snake_case")`  | All JSON boundaries — inbound request bodies, outbound response bodies, and the outbound webhook payload (`TimerEvent`) — go through typed records. Component names compile to camelCase so the annotations are mandatory.                                                                                   |
| `webhook_subscribe` / `/unsubscribe` HTTP contract  | The three endpoints under `/webhook/*` implement the SDK contract documented in `README-extension-api.md` verbatim. Subscription identity is an extension-generated UUID — the recommended path per that document.                                                                                           |
| `@Value("${min-period-seconds:5}")`                 | Soft floor on the period, overridable from YAML — tests set it to `1` so the scheduler can tick inside a bounded wait.                                                                                                                                                                                       |
| `DisposableBean.destroy()` lifecycle                | Cancels in-flight `ScheduledFuture`s, shuts the executor down, and `awaitTermination`s. Fires when the extension's Spring context closes (profile unload or `AgentExtensionHarness.stop()` in tests).                                                                                                        |
| Lazy init of a shared `ScheduledExecutorService`    | Double-check-locked, daemon-threaded, named `timer-ext-<n>`. Same pattern as **NtlmExtension**'s HttpClient.                                                                                                                                                                                                 |
| `scheduleWithFixedDelay` over `scheduleAtFixedRate` | `AgentWebhookApiV1.send` is **synchronous and blocks for the whole retry chain** (seconds, not millis). Fixed-rate would queue up back-to-back ticks on the single-threaded executor during a slow one; fixed-delay keeps a full `period_seconds` gap between the end of one tick and the start of the next. |
| Swallow + log in the scheduler thread               | A `WebhookDeliveryException` on a scheduled tick is caught, logged `WARN`, and the thread stays alive to run the next tick. Contrast with `/fire_now` which propagates the exception to the HTTP response — manual triggers want visible failures.                                                           |

Not demonstrated here (see other extensions):

- `AgentExtensionContextV1.getProfileConfiguration()` map read → **SecurityExtension**.
- `@RequestHeader`-driven controllers → **NtlmExtension**.
- Writing `HttpServletResponse` status directly → **NtlmExtension**.

## HTTP endpoints

All under `/ext/{profile}/webhook/*`.

| Method | Path           | Request                                                                    | Response                                                                                       |
|--------|----------------|----------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `GET`  | `/health`      | —                                                                          | `200 OK`                                                                                       |
| `POST` | `/subscribe`   | `{workato_webhook_url, recipe_id, connection_id, message, period_seconds}` | `200 {"id":"<uuid>"}` — or `400 {"error":"period_seconds must be >= <min>"}`                   |
| `POST` | `/unsubscribe` | `{recipe_id (= our UUID), connection_id}`                                  | `204 No Content`                                                                               |
| `POST` | `/fire_now`    | `{subscription_id}`                                                        | `200 {"delivered": <bool>, "status_code": <int>}` or `404 {"error":"unknown subscription_id"}` |

Outbound tick payload — the body `AgentWebhookApiV1.send(...)` POSTs to
`workato_webhook_url`:

```json
{
  "message": "<as supplied at subscribe>",
  "generated_at": "2026-10-19T14:03:21.337Z",
  "tick": 1,
  "subscription_id": "<uuid>",
  "period_seconds": 10
}
```

## Configuration

Only `controller:` is required. Everything else is optional — the extension
runs out of the box with the YAML snippet below:

```yaml
extensions:
  timer:
    controller: com.mycompany.onprem.timer.TimerExtension
```

Full configuration surface, every key shown with its default:

```yaml
extensions:
  timer:
    controller: com.mycompany.onprem.timer.TimerExtension

    # Optional — soft floor on period_seconds a recipe may request.
    # Default: 5. Tests lower it to 1 so the scheduler can tick inside a
    # bounded wait.
    min-period-seconds: 5

    # Optional — per-profile override of the retry policy
    # AgentWebhookApiV1.send(...) uses. Omitting the whole `webhook:` block
    # leaves every key at its default (5 attempts, 1s → 2s → 4s → 8s backoff,
    # no jitter, 30 s per-attempt timeout, 1 MB response cap). Individual
    # keys inside the block are also optional — a typo on one key logs a
    # WARN and falls back to the default for *that key only*, the profile
    # still loads.
    webhook:
      retries: 5                   # default 5
      initial-delay-ms: 1000       # default 1000
      multiplier: 2.0              # default 2.0
      max-delay-ms: 30000          # default 30000
      jitter-factor: 0.0           # default 0.0, max 1.0
      timeout-ms: 30000            # default 30000
      response-cap-bytes: 1048576  # default 1 MB
```

- `min-period-seconds` — rejects `/subscribe` calls with a smaller
  `period_seconds`. Keeps a demo from DoS-ing the recipe.
- `webhook:` — read by the agent's `AgentWebhookApiV1` implementation, not
  by our code. See `extension-api/README-extension-api.md` section
  "Configuration via YAML" for the full policy semantics.

## Workato connector

Companion Ruby connector: [`src/main/ruby/timer/connector.rb`](../../../../../main/ruby/timer/connector.rb).
Exposes the trigger `tick` (full `webhook_subscribe` / `_notification` /
`_unsubscribe` lambdas) and an `fire_now` action for one-shot delivery. The
connector caches `connection_id` inside the subscribe-output hash so
`webhook_unsubscribe` — which the SDK calls with only that hash, no
connection — can rebuild the unsubscribe body without a side-channel lookup.
