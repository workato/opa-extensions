# SecurityExtension

Simplest extension in the repo. One synchronous endpoint that takes a UTF-8
string from a Workato recipe, prefixes it with a secret the agent reads from
`config.yml`, and returns the SHA-256 digest.

The point is to show the **smallest possible shape** of an OPA extension — a
plain Spring `@RestController` that pulls one key out of its YAML profile and
answers one `POST`. Everything else in the project is built on this skeleton.

## Extension API features demonstrated

| Feature                                                 | How                                                                                                                                                                                                                      |
|---------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Autowiring `AgentExtensionApiV1`                        | `@Inject AgentExtensionApiV1 agent;` — jakarta.inject form, equivalent to Spring `@Autowired`.                                                                                                                           |
| `AgentExtensionContextV1.getProfileName()`              | Used as a prefix in every log line so multi-profile agents are debuggable.                                                                                                                                               |
| `AgentExtensionContextV1.getProfileConfiguration()`     | Reads the `signing-secret` key out of the profile's YAML map at request time.                                                                                                                                            |
| YAML → config read via the context (not `@Value`)       | A deliberate choice — shows the `Map<String, Object>` view, including the subtle gotcha that `secret` as a key name is reserved by the agent's `SecretsPreprocessor` and must be avoided (see the comment in the class). |
| `@RestController` + `@PostMapping` + `@RequestBody Map` | Vanilla Spring MVC — no custom body deserialisation.                                                                                                                                                                     |
| Failing fast on missing config                          | `IllegalStateException` carrying the profile name — surfaces to the caller as HTTP 500 but with a message a human can act on.                                                                                            |

Not demonstrated here (see other extensions):

- `@Value("${…}")` property injection → **NtlmExtension**.
- Lazy init of a shared resource via double-check locking → **NtlmExtension**.
- Outbound HTTP via `AgentWebhookApiV1.send(...)` → **TimerExtension**.
- Background scheduler + `DisposableBean.destroy()` lifecycle → **TimerExtension**.

## HTTP endpoint

```
POST /ext/{profile}/computeDigest
Content-Type: application/json

{"payload": "<string>"}
```

Response:

```
200 OK
Content-Type: application/json

{"signature": "<64-char lowercase hex of SHA-256(secret || payload)>"}
```

## Configuration

```yaml
extensions:
  security:
    controller: com.mycompany.onprem.security.SecurityExtension
    signing-secret: <any string>
```

- `signing-secret` (required, non-empty) — prepended to the payload before
  hashing. **Do not** rename this key to just `secret` — the agent's
  `SecretsPreprocessor` interprets any map containing `secret:` as a
  secret-provider spec and will fail to load the profile in local-HTTP mode.

## Workato connector

Companion Ruby connector: [`src/main/ruby/security/connector.rb`](../../../../../main/ruby/security/connector.rb).
It exposes one action (`sha256_digest`) that forwards `{payload}` to this
endpoint and surfaces the returned signature.
