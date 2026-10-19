# NtlmExtension

Proxies an HTTPS/HTTP GET through NTLM authentication. The Workato recipe
hands the extension a target URL and credentials; the extension performs the
NTLM-authenticated request from inside the on-prem network and streams the
response body back verbatim.

Exists because Workato cloud cannot speak NTLM directly to a customer's
intranet host — the handshake only works from a process that lives inside the
customer's network boundary. This is the archetype of why on-prem extensions
exist in the first place.

## Extension API features demonstrated

| Feature                                              | How                                                                                                                                                                       |
|------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `@Autowired AgentExtensionApiV1`                     | Spring form of the autowire — alternative to `@Inject` seen in **SecurityExtension**.                                                                                     |
| `@Value("${connect-timeout-ms:5000}")` with defaults | Property injection with a fallback; honours the agent's dot-literal lookup semantics (dots in keys are literal, not nesting markers).                                     |
| Multiple injected scalars + a list-shaped key        | `connect-timeout-ms`, `read-timeout-ms`, `ntlm.allowed-hosts` — the last one is parsed into a comma-separated allow-list.                                                 |
| Lazy init of a shared resource (DCL pattern)         | `volatile CloseableHttpClient httpClient;` + double-check locking in `client()`. Mirrors the pattern **TimerExtension** uses for its `ScheduledExecutorService`.          |
| Request headers via `@RequestHeader`                 | NTLM creds and the target URL arrive as agent-side request headers (`X-Workato-NTLM-auth` / `X-Workato-NTLM-url`), not JSON body — reduces chances of accidental logging. |
| Writing to the HTTP response status directly         | Uses `HttpServletResponse.setStatus(...)` to forward upstream status codes (400 for bad URL, 403 for host not in allow-list, upstream's 2xx/4xx/5xx otherwise).           |

Not demonstrated here (see other extensions):

- `AgentExtensionContextV1.getProfileConfiguration()` map read → **SecurityExtension**.
- Outbound HTTP via `AgentWebhookApiV1.send(...)` → **TimerExtension**.
- Background scheduler + `DisposableBean.destroy()` lifecycle → **TimerExtension**.

## HTTP endpoint

```
POST /ext/{profile}/request
X-Workato-NTLM-auth : username:password[@domain[/workstation]]
X-Workato-NTLM-url  : <absolute http(s) URL>
```

Response:

```
200 OK + upstream body       — upstream answered 2xx
400 Bad Request              — target URL malformed / non-http scheme / no host
403 Forbidden                — target host not in ntlm.allowed-hosts
<upstream non-2xx status>    — forwarded as-is, empty body
```

## Configuration

```yaml
extensions:
  ntlm:
    controller: com.mycompany.onprem.ntlm.NtlmExtension
    connect-timeout-ms: 5000          # default 5000
    read-timeout-ms: 30000            # default 30000
    ntlm.allowed-hosts: "intranet.corp.local, *.corp.local"
```

- `connect-timeout-ms` / `read-timeout-ms` — straight to Apache HttpClient's
  `RequestConfig`. Optional; defaults in the `@Value` annotations.
- `ntlm.allowed-hosts` — comma-separated. Each entry is matched
  case-insensitively against the target URL's host. `*.suffix` matches any
  single-level-or-deeper subdomain. **Empty value disables the check**
  entirely — fine for a lab, risky in production. Configure explicitly.

## Workato connector

Companion Ruby connector: [`src/main/ruby/ntlm/connector.rb`](../../../../../main/ruby/ntlm/connector.rb).
Credentials live in the Workato connection (not in each action input), get
assembled into the `X-Workato-NTLM-auth` header via a shared connector
`method`, and the target URL is the action's one input field.
