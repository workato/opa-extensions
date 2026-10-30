OPA extension SDK allows customers, external developers/partners to connect to legacy on-prem systems using Java within
the Workato Agent infrastructure.

## Available extensions

Each extension is a standalone Java `@RestController` plus a companion Workato
custom connector. Per-extension documentation (what it does, what Extension
API features it demonstrates, HTTP endpoints, YAML keys it reads) lives next
to its Java class — follow the links.

| Extension                                                                                     | What it demonstrates                                                                                                                                                                                                                                                                  | HTTP surface                                                            |
|-----------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|
| **[security](src/main/java/com/mycompany/onprem/security/README.md)**                         | The minimum-viable extension — `@Inject AgentExtensionApiV1`, YAML read via `getProfileConfiguration()`, one `@PostMapping` endpoint.                                                                                                                                                 | `POST /computeDigest`                                                   |
| **[ntlm](src/main/java/com/mycompany/onprem/ntlm/README.md)**                                 | `@Value("${…}")` property injection with defaults, lazy-init of a shared `HttpClient` (DCL pattern), `@RequestHeader`-driven inputs, forwarding upstream status via `HttpServletResponse`.                                                                                            | `POST /request`                                                         |
| **[timer](src/main/java/com/mycompany/onprem/timer/README.md)**                               | **`AgentWebhookApiV1.send(...)`** with per-profile retry policy, `webhook_subscribe` / `_unsubscribe` HTTP contract, Jackson records with `@JsonProperty`, background `ScheduledExecutorService` with `DisposableBean.destroy()` lifecycle.                                           | `GET /webhook/health`, `POST /webhook/{subscribe,unsubscribe,fire_now}` |
| **[configuration_sample](src/main/java/com/mycompany/onprem/configuration_sample/README.md)** | **Cloud-managed model** (`@AgentConfigSupport`), **`AgentConnectionLifecycleV1`** callbacks (`onConnectionAdded/Updated/Removed`), raw-type `@Value` for static per-adapter vs `@Value Supplier<T>` for connection-dynamic resolution, singleton controller serving many connections. | `GET /{connectionId}/test`, `GET /{connectionId}/info`                  |

Add a new extension by cloning any of the subdirectories under
`src/main/java/com/mycompany/onprem/` and following the six-step **Development
workflow** below.

## Two extension models

An OPA extension comes in one of two flavours. Pick before you start — the
differences cascade into every file you will write.

|                                     | **Per-profile** (static)                                                  | **Cloud-managed** (dynamic)                                                                  |
|-------------------------------------|---------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| Demonstrated by                     | `security`, `ntlm`, `timer`                                               | `configuration_sample`                                                                       |
| How the agent learns about it       | Admin edits `extensions:` in `conf/config.yml` by hand on every agent     | Workato pushes `custom.{adapter}.connections.{id}` to the agent; admins never touch it       |
| YAML namespace (admin writes)       | `extensions.{profile}`                                                    | `agent-config-support.{adapter}` (static per-adapter; optional)                              |
| YAML namespace (cloud-delivered)    | — (nothing)                                                               | `custom.{adapter}.connections.{id}`                                                          |
| URL layout                          | `/ext/{profile}/<path>`                                                   | `/ext/{adapter}/{connectionId}/<path>`                                                       |
| How the controller is located       | `controller:` property gives the FQCN                                     | `@AgentConfigSupport(name = "…")` annotation on the class                                    |
| Controller instances                | One instance per profile; each profile is a separate controller + context | One instance per adapter; the same controller serves every connection of the adapter         |
| Connection fields in `connector.rb` | `profile` field is **required** and must match the YAML key               | `profile` field is **forbidden**; routing goes through `_connection_id` set by the framework |
| Hot-reload                          | No — editing `config.yml` requires an agent restart                       | Yes — the framework delivers connection diffs through `AgentConnectionLifecycleV1` callbacks |

If you need multi-tenancy, connection-field edits from the Workato UI, or
dynamic registration without touching every agent — use **cloud-managed**.
If you have a single long-lived per-agent setup (one SMB share, one NTLM
realm, one scheduled task) — **per-profile** is simpler.

## Configuration

The extension mechanism is enabled by adding the `extensions:` and/or
`agent-config-support:` section to the agent config file, `conf/config.yml`.

**Per-profile extensions** — admin writes everything, one block per profile:

```yml
server:
  classpath: /opt/workato/agent/lib_ext

extensions:
  security:
    controller: com.mycompany.onprem.security.SecurityExtension
    signing-secret: HA63A3043AMMMM
```

Every key under `extensions:` names one profile; each profile is URL-friendly
(valid identifier, no whitespace or special characters). `controller:` is the
fully-qualified name of the Spring `@RestController` class. Every sibling key
is a configuration value the controller reads at request time.

**Cloud-managed extensions** — admin pins static per-adapter values only;
Workato delivers everything per-connection:

```yml
server:
  classpath: /opt/workato/agent/lib_ext

agent-config-support:
  configuration_sample:
    environment: prod            # static, same for every connection of this adapter
```

No `controller:` key — the agent finds the class via its
`@AgentConfigSupport(name = "configuration_sample")` annotation at startup.
No connection-level block in `config.yml` either — those arrive from the
Workato cloud and the framework reloads them without a restart.

`server.classpath` is a comma- or newline-separated list of directories /
JAR files containing compiled extensions. Point it at the directory you drop
the extension JAR into (**`lib_ext/`** by convention — see **Installing the
extension to OPA**).

## Using extension endpoints

The URL layout depends on the model (see **Two extension models** above):

- **Per-profile**: `/ext/{profile}/<path>` — `{profile}` is the key under
  `extensions:` in `config.yml`; `<path>` is the Spring `@RequestMapping`.
- **Cloud-managed**: `/ext/{adapter}/{connectionId}/<path>` — `{adapter}`
  is the `@AgentConfigSupport(name = …)` value; `{connectionId}` is the
  numeric id Workato assigns to the connection; `<path>` is the Spring
  `@RequestMapping`.

The authoritative API surface — allowed packages, injection points,
lifecycle, worked examples — is in
[`extension-api/README-extension-api.md`](extension-api/README-extension-api.md),
regenerated from the Extension API JAR on every compile.

For concrete examples of each pattern in use, open the per-extension READMEs
linked in the **Available extensions** table above.

## Writing the Java extension — common rules

These rules apply to every OPA extension regardless of model. Features
specific to one model are tagged **[cloud-managed]**.

### Return 4xx with a parseable JSON body

The companion `connector.rb` relies on an `after_error_response` hook to
pull a human message out of your 4xx body — so give it one. A consistent
shape across every error endpoint also makes action-level `execute:` errors
usable in recipes:

```java
return ResponseEntity.badRequest().

body(Map.of(
        "status", "error",
             "error","timeout must be > 0",   // human message, surfaced in the UI
             "timeout",t));                   // raw value for debugging
```

If you return `ResponseEntity.badRequest().build()` (no body) the UI falls
back to a bare "400 Bad Request" and your users won't know what went wrong.

### YAML read: dot-literal keys, reserved key names

- **Dots in YAML keys are literal, not nesting markers.** A
  `connect-timeout-ms:` key and a `connect-timeout-ms-override:` key are two
  unrelated entries; `@Value("${connect-timeout-ms}")` reads the first one
  verbatim, no path parsing.
- **Reserved key names** — avoid for configuration values:
    - **`secret`** — the agent interprets any map containing `secret:` as a
      secret-provider spec and will fail to load the profile in local-HTTP
      mode. Pick `signing-secret`, `api-token`, `pass`, anything non-generic.
    - **`controller` / `controllerClass`** — reserved by the per-profile
      configuration schema; the agent uses them to resolve your controller
      class and refuses to pass them through to your extension.

### Controller is a singleton; guard mutable state yourself [cloud-managed]

For cloud-managed extensions one controller instance serves every
connection of the adapter, from every thread, for the whole life of the
agent. Any plain instance field is shared state. Keep per-connection data
in a `ConcurrentHashMap<Long, …>` keyed by `connectionId`, keep per-request
data in local variables inside the handler, and do not touch raw instance
fields without one of those scopes.

```java
private final ConcurrentHashMap<Long, Instant> updatedAt = new ConcurrentHashMap<>();

@Override
public void onConnectionUpdated(long connectionId, AgentConfiguration cfg) {
    updatedAt.put(connectionId, Instant.now());
}
```

A `private Instant updatedAt` written from `onConnectionUpdated` would make
every connection's response reflect whichever connection last updated —
subtle, deadly under load.

Per-profile extensions get one controller per profile out of the box, so
cross-connection contamination is impossible — but concurrent requests to
the same profile are still a concern. The same rule applies to any mutable
field a request handler writes.

### Lifecycle callbacks run outside any HTTP request [cloud-managed]

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

### Detecting a connection edit [cloud-managed]

Two complementary signals are available — pick whichever fits:

**Framework-provided: `agent.getContext().getConnectionCreatedAt(): Instant`.**
Returns the wall-clock instant at which the agent first observed the
connection currently being served. The value is captured at the moment of
the `onConnectionAdded` callback and does not change on in-place config
updates. However, Workato performs Disconnect-then-Connect transparently
during a connection edit — so the agent sees a remove-then-add and the
timestamp is refreshed on every edit. Returns `null` for legacy /
out-of-request / unknown-connection sentinels; always check.

**Author-provided: lifecycle listener.** If you implement
`AgentConnectionLifecycleV1` on your controller, record `Instant.now()` from
the three callbacks (typically `onConnectionAdded` + `onConnectionUpdated`
set it, `onConnectionRemoved` clears it) in a `ConcurrentHashMap<Long,
Instant>` keyed by `connectionId`. Full control over the semantics — for
example, `onConnectionUpdated` would refresh an author-tracked timestamp on
every merged-config change, whereas the framework's `getConnectionCreatedAt`
does not.

The two change at different moments: the API-provided value is stable on
in-place updates, author-tracked state is only as stable as the author
makes it. Both reset to a fresh timestamp on a Workato edit (because of
the Disconnect-then-Connect flow underneath).

Canonical lazy cache invalidation uses either approach as the version key
of a `ConcurrentHashMap<Long, Cached>`:

```java
record Cached(Instant createdAt, PooledHttpClient client) {}
private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

@PostMapping("/{connId}/op")
public Result op(@PathVariable long connId) {
    Instant now = agent.getContext().getConnectionCreatedAt();
    Cached c = cache.compute(connId, (k, old) -> {
        if (old != null && old.createdAt.equals(now)) return old;  // unchanged
        if (old != null) old.client.close();                        // stale — close pool
        return new Cached(now, buildClient(agent.getContext().getProfileConfiguration()));
    });
    return call(c.client);
}
```

Don't persist either timestamp, don't compare across agent restarts, don't
log either as authoritative state.

### Stateless by default across the agent group

OPA agents commonly run as a group of several instances behind a load
balancer. Workato routes each request to one arbitrary member of the
group — the member that handled yesterday's call may not be the one that
handles today's. **An extension should assume nothing in memory survives
to the next request.** If you must keep state (subscription registries,
caches larger than per-connection, scheduled tasks), source it from a
shared store (database, Redis) or push it through an external system that
every group member can observe. See `timer/README.md` for a concrete
illustration of the limitation.

## Development workflow

End-to-end for a new extension. The six steps below mirror the layered test
pyramid that already covers every extension in **Available extensions** above;
each has its own detailed section further down with code, Gradle commands, and
troubleshooting.

```
┌────────────────────────────────┐                         ┌──────────────────────────────────┐
│ 1. Java extension class        │  src/main/java/com/     │ 2. AgentExtensionHarness tests   │
│    (Spring @RestController)    │  mycompany/onprem/      │    (Spring MockMvc, no agent,    │
│                                │     <name>/             │     no network)                  │
└───────────────┬────────────────┘                         └────────────────┬─────────────────┘
                │                                                           │
                ▼                                                           ▼
┌────────────────────────────────┐                         ┌──────────────────────────────────┐
│ 3. Ruby connector.rb           │  src/main/ruby/<name>/  │ 4. Workato Connector SDK specs   │
│    (secure_tunnel: true,       │  src/test/ruby/<name>/  │    (RSpec + VCR cassettes,       │
│     http://localhost/ext/…)    │                         │     no network)                  │
└───────────────┬────────────────┘                         └────────────────┬─────────────────┘
                │                                                           │
                └──────────────────────────┬────────────────────────────────┘
                                           ▼
                         ┌────────────────────────────────────────┐
                         │ 5. DeployedAgentLauncher integration   │
                         │    tests against a real on-disk OPA    │
                         │    (openLocalAccess + gateway)         │
                         └────────────────┬───────────────────────┘
                                          ▼
                         ┌────────────────────────────────────────┐
                         │ 6. Full-chain debug: real recipe in    │
                         │    Workato cloud → gateway tunnel →    │
                         │    local agent → IDE breakpoint        │
                         └────────────────────────────────────────┘
```

### Step 1 — Write the Java extension class

Put the controller under `src/main/java/com/mycompany/onprem/<name>/` along
with a `README.md` next to it (what it does + which Extension API features it
demonstrates — mirror the shape of the existing per-extension READMEs linked
in **Available extensions**). It is a regular Spring `@RestController` the
agent will mount at `/ext/<profile-name>/...`. Follow
`extension-api/README-extension-api.md` for the exhaustive contract (allowed
packages, injection points, lifecycle). Any existing extension listed in
**Available extensions** is a working example.

Make the controller methods throw only unchecked exceptions — this keeps
their method references usable with the typed `DeployedAgentLauncher`
helpers in step 5 (`Ref1..Ref10` do not declare `throws`).

### Step 2 — Unit-test the controller with `AgentExtensionHarness` (no OPA)

Add `<Name>Test` under `src/test/java/com/mycompany/onprem/<name>/`. The
harness wires your controller into a hermetic Spring `MockMvc` context — no
real HTTP server, no network, nothing that mimics the agent's own
packaging. It takes milliseconds and runs on every `./gradlew test`.

Covers: `@Autowired AgentExtensionApiV1`, `@Value("${…}")` injection with
dot-literal semantics, `/ext/{profile}/*` routing, `@PathVariable` /
`@RequestBody`, your `@ControllerAdvice` beans. Does *not* cover: the
agent's own packaging, mTLS tunnel, anything that only shows up when the
code runs inside a deployed agent. Full DSL + troubleshooting table: see
**Java extension tests** below and `extension-api/README-extension-test.md`.

### Step 3 — Write the Ruby connector

Put `connector.rb` under `src/main/ruby/<name>/connector.rb`. The filename
is mandatory (Workato SDK contract). What the file must contain depends on
the extension model you chose (see **Two extension models** above).

Shared rules — both models:

- Declare `secure_tunnel: true` — forces every HTTP call through an on-prem
  agent group instead of leaving Workato's cloud.
- Address the agent as `http://localhost/ext/…` — literal `localhost` is
  the sentinel the tunnel resolves to the agent.
- Send header `X-Workato-Connector: enforce` on every request — Workato
  rejects the call if no on-prem connection group is attached.
- **Surface the extension's error messages via `after_error_response`.**
  The SDK's default on a non-2xx is to raise `RequestFailedError` with a
  generic `"<code> <reason>"` and drop the body. The UI then shows just
  "Connection failed: 400 Bad Request". Pull the body apart and re-raise:

  ```ruby
  .after_error_response(/.*/) do |_code, body, _headers, message|
    payload = (parse_json(body) rescue nil) || {}
    error(payload['error'].presence || "#{message}: #{body}")
  end
  ```

  (Pairs with the "Return 4xx with a parseable JSON body" rule in the
  Writing-the-Java-extension section above.)
- **Workato auto-titles output fields.** The UI generates human labels from
  field names by splitting camelCase — `connectionVersion` renders as
  "Connection version", `updatedAt` as "Updated at". JSON keys don't
  change, only labels on pills do. Override with explicit `label:` when
  needed.

#### Per-profile connector

- Declare one connection field named `profile`. Its value becomes the
  `{profile}` segment in the URL and must match a key under `extensions:`
  in the agent's `config.yml` — exact string, case-sensitive.
- Build URLs as `http://localhost/ext/#{connection['profile']}/<endpoint>`.

Full examples: `src/main/ruby/security/connector.rb`,
`src/main/ruby/ntlm/connector.rb`, `src/main/ruby/timer/connector.rb`.

#### Cloud-managed connector

```ruby
opa_extension_name = 'configuration_sample'

{
  title: "On-prem #{opa_extension_name}",
  secure_tunnel: true, # mandatory
  opa_extension_name: opa_extension_name, # mandatory, must match @AgentConfigSupport(name=…)
  ...
}
```

- **`opa_extension_name:`** must match the `@AgentConfigSupport(name = "…")`
  on the Java class byte-for-byte, including case. A typo yields a bare
  `404 Not Found` on every request with no helpful log. Keep the value in a
  local variable (`opa_extension_name = '…'`) and interpolate it everywhere
  else — single source of truth for the name.
- **URL shape:**
  ```ruby
  "http://localhost/ext/#{opa_extension_name}/#{connection['_connection_id']}/<path>"
  ```
  Workato populates `connection['_connection_id']` for `secure_tunnel: true`
  connectors — the lambda receives it in the `connection` hash. **Do not
  invent your own key** (`connection['id']`, `connection['conn_id']`, …) —
  only `_connection_id` is populated by the framework.
- **Do not add a connection field named `profile`.** A `profile` field in
  `connection.fields` makes Workato route the connector through a
  different legacy pathway and your extension stops receiving
  configuration. Pick anything else: `env`, `label`, `tenant`,
  `instance_name` — all fine.

Full example: `src/main/ruby/configuration_sample/connector.rb`.

#### Common `connector.rb` mistakes

- **Hard-coding the extension name all over the file** — easy to typo one
  of the copies. Keep the name in a local variable at the top, interpolate
  everywhere else.
- **Forgetting `secure_tunnel: true`** — the connector loads, the UI
  renders the form, but every request leaves Workato cloud instead of
  reaching the agent. Hard to debug because the symptom is "cannot reach
  localhost".
- **Dropping the mandatory URL segment** (`profile` for per-profile,
  `_connection_id` for cloud-managed) — common on copy-paste. The path
  shrinks by one segment and the extension returns `404`.
- **Letting the extension's 4xx body fall on the floor** — see
  `after_error_response` above.
- **Naming a connection field `profile` in a cloud-managed connector** —
  see above; reserves the field, flips the connector back to the legacy
  routing path.
- **Mixing old DSL keys** — `agent_extension:` was renamed to
  `opa_extension_name:`. Only the latter is read.

### Step 4 — Spec the connector with the Workato Connector SDK (no OPA)

Add `src/test/ruby/<name>/spec/connector_spec.rb`. The spec resolves the
connector via `CONNECTOR_PATH = File.expand_path('../../../../main/ruby/<name>/connector.rb', __dir__).freeze`
and asserts behaviour against VCR cassettes under `src/test/resources/tape_library/<name>/`. The
harness is pinned to `record: :none`, so an unmocked request fails loudly
rather than silently reaching the network. Full workflow (hand-editing
cassettes, re-recording against a live agent, cleanup before commit): see
**Ruby custom-connector tests** below.

### Step 5 — Integration-test against a real OPA (`DeployedAgentLauncher`)

Add `<Name>DeployedTest` under `src/test/java/com/mycompany/onprem/<name>/`.
This is the first step that touches an actual agent on disk. The launcher
starts a real agent process in your test JVM with your extension class
loaded into it and your `config.yml` applied. Keep these tests `@Disabled`
— they depend on per-machine state (deployed agent path, activated certs)
and are run manually from the IDE.

Two complementary modes, both are present in `*DeployedTest`:

- **`openLocalAccess()` + direct HTTP** — opens a local port on the agent
  and calls the controller via `agent.httpPost(Controller::method, body)` /
  `agent.requestFor(Controller::method).header(…).build()` + `agent.send(req)`.
  Verifies the full HTTP serving path inside a real agent without needing
  the Workato cloud. In this mode the agent does **not** connect to the
  gateway (by design — see the mode table in
  `extension-api/README-extension-test.md`).
- **No `openLocalAccess()` + `awaitGatewayConnected(Duration)`** — the agent
  stays in full gateway mode and the test blocks until the
  `"Connected to gateway"` marker appears on stdout. Verifies activation
  certs + egress to `sg*.workato.com` + mTLS handshake. This is the mode
  used as the launchpad for step 6.

### Step 6 — Debug the full Workato → OPA chain

Once your `connectsToWorkatoGateway` test consistently passes, use it as a
long-lived harness for debugging real recipe runs:

1. **Start `<Name>DeployedTest.connectsToWorkatoGateway` in the IDE's
   Debug runner.** The in-JVM agent completes the mTLS handshake and shows
   up as online in the Workato workspace's on-prem agent group — exactly as
   if it were started via `bin/run.sh`.
2. **Set the controller breakpoint to suspend _only the current thread_,
   not all threads.** In IntelliJ: right-click the red dot → Suspend →
   **Thread** (default is **All**). This is non-negotiable — if you suspend
   all threads, the agent's heartbeat scheduler freezes, the gateway side
   decides the agent is dead and tears the tunnel down, and the breakpoint
   just hangs. Thread-suspend keeps the heartbeat loop + Jetty acceptor
   running while your controller frame is paused.
3. **Publish the connector to Workato.** Either paste `src/main/ruby/<name>/connector.rb`
   into the Workato UI editor, or run `workato push` from
   `src/test/ruby/<name>/` with `--connector ../../../main/ruby/<name>/connector.rb`.
   See **Deploying the connector to Workato** below for both paths.
4. **In Workato, build a recipe that calls your action.** Create a
   Connection against the custom connector and bind it to the on-prem
   agent group your activated cert belongs to.
    - For a per-profile connector: set the `profile` field to the same name
      the DeployedTest launched (`"security"` / `"ntlm"` by default).
    - For a cloud-managed connector: nothing extra — the framework assigns
      the connection id, the agent picks it up on the next cloud push.

   Any trigger will do — "Run on demand" is the shortest path.
5. **Trigger the recipe.** The request leaves the Workato cloud → traverses
   the mTLS tunnel → arrives at the in-JVM agent → hits
   `/ext/<profile>/<endpoint>` → fires your breakpoint. From that frame the
   IDE's call stack walks the full chain from the agent's HTTP front-end
   down to your controller. Step through, mutate variables, evaluate
   expressions — the rest of the agent keeps serving the gateway on other
   threads.

This loop is the fastest way to reproduce production-only bugs — library
version clashes with what the agent already ships, HTTP buffering, header
handling in the tunnel — without any build/deploy churn: rebuild only your
extension class, re-run the test, iterate.

## Project setup: Extension API and test-harness JARs

Both the compile and test classpaths depend on two JARs shipped with your
Workato agent distribution. Before the project will build, copy them from the
distribution into the project's `extension-api/` directory:

| File                           | Source                                     | Gradle scope         | Purpose                                                      |
|--------------------------------|--------------------------------------------|----------------------|--------------------------------------------------------------|
| `extension-api-<version>.jar`  | agent distribution (same version as agent) | `implementation`     | Production API your extension compiles against.              |
| `extension-test-<version>.jar` | agent distribution (same version as agent) | `testImplementation` | Mock/harness used by tests that run without a running agent. |

Both JARs **must come from the same agent distribution** — the version suffix
has to match. Mixing versions is a startup bug waiting to happen; the build
rejects it before you get there.

Resulting layout:

```
extension-api/
├── extension-api-<version>.jar     ← you copy this
├── extension-test-<version>.jar    ← you copy this
├── README-extension-api.md         ← generated on each build
└── README-extension-test.md        ← generated on each build
```

### What the build verifies

Every compile runs the `validateExtensionJars` task, which fails the build with
a copy-them-from-the-agent hint if any of the following is true:

- the `extension-api/` directory is missing;
- either JAR is absent;
- more than one version of either JAR is present;
- the two JARs come from different versions.

### Extracted documentation

The build also runs `extractExtensionReadmes`, which pulls `README.md` out of
each JAR into `extension-api/`:

- **[`extension-api/README-extension-api.md`](extension-api/README-extension-api.md)**
  — the authoritative reference for writing an extension (API surface,
  configuration access, lifecycle, injection, migration from legacy types).
  Read this before writing production code.
- **[`extension-api/README-extension-test.md`](extension-api/README-extension-test.md)**
  — the authoritative reference for testing an extension with the mocked API
  (test harness setup, `MockMvc` usage, property/YAML wiring, troubleshooting).
  Read this before writing tests.

Both files are regenerated from the JARs on every build, so they always match
the version currently on the classpath. Prefer them over any snippets in this
README or elsewhere.

#### How the regeneration is wired

The `extractExtensionReadmes` task in `build.gradle` owns the invariant
*"the two README files always reflect the JARs currently in `extension-api/`"*.
The mechanism:

1. **Inputs**: `fileTree(dir: 'extension-api', include: 'extension-*.jar')` —
   the two shipped JARs.
2. **Outputs**: `extension-api/README-extension-api.md` and
   `extension-api/README-extension-test.md`.
3. **Action**: open each JAR as a ZIP, read the `README.md` entry, write it to
   the corresponding output file. If a JAR has no `README.md` entry the build
   fails loudly.
4. **Up-to-date policy**: `outputs.upToDateWhen { false }`. The task is
   deliberately *never* considered up-to-date, so it re-extracts on every
   invocation — hand-edits to the output files never survive a build.
5. **Wiring to the standard graph**:
   ```groovy
   tasks.withType(AbstractCompile).configureEach { it.dependsOn extractExtensionReadmes }
   processResources.dependsOn extractExtensionReadmes
   ```
   Every path that compiles Java or packages resources (`./gradlew build`,
   `check`, `test`, `compileJava`, `assemble`, …) therefore triggers extraction
   before any Java code runs.
6. **Precondition**: `extractExtensionReadmes` `dependsOn validateExtensionJars`,
   so a missing or version-mixed `extension-api/` directory stops the build
   *before* extraction is attempted.

Operationally this guarantees:

- A fresh `git clone` + `./gradlew build` produces matching READMEs.
- Bumping the JAR version (e.g. `extension-api-34.0.jar` → `-35.0.jar`) causes
  the next build to overwrite the READMEs with the new version's content.
- A README accidentally edited by hand is reverted on the next build.
- Deleting a README (`rm extension-api/README-extension-api.md`) is harmless —
  the next build recreates it.

> This repository already contains a wired-up `build.gradle` that applies both
> JARs to the right scopes. If you start a project from scratch, use the
> `dependencies { ... }`, `validateExtensionJars` and `extractExtensionReadmes`
> blocks in this project's `build.gradle` as the reference.

## Building extension

Steps to build an extension:

1. Install the latest [Java 21 SDK](https://adoptium.net/).
2. Copy the two JARs from the agent distribution into `extension-api/`
   (see the preceding section).
3. Run `./gradlew jar` — this also triggers `validateExtensionJars` and
   `extractExtensionReadmes`.
4. The output is `build/libs/opa-extensions-<version>.jar`.

The `jar` task produces a **minimal fat jar** — it bundles only the Apache
libraries that extension code actually imports (today: `commons-codec` for
`Hex`, `commons-lang3` for `StringUtils`), plus our own classes under
`com/mycompany/onprem/`. Everything else the agent provides at runtime —
`extension-api`, Spring, Jackson, Jakarta, SLF4J, Apache HttpClient — is
declared `compileOnly` in `build.gradle` and is **not** shaded. Shipping
your own copy of what the agent already provides risks version clashes at
load time (see `extension-api/README-extension-api.md` → "Add the API as a
provided-scope dependency") and bloats the deliverable; today the jar
weighs under 1 MB.

When you add a new third-party dependency, decide its scope with one
question: *does the agent itself provide this at runtime?*

- **Yes** (any Spring / Jackson / Jakarta / SLF4J / Apache HttpClient
  module, further `com.workato.*` types) → `compileOnly`. Nothing extra
  for tests — the `configurations { testImplementation.extendsFrom
  compileOnly }` block near the top of `dependencies { }` already forwards
  every `compileOnly` entry onto the test classpath, so
  `AgentExtensionHarness` keeps working.
- **No** (anything else — any new Apache Commons module, any client library
  for the system this extension talks to) → `implementation`. It gets
  shaded into the fat jar. Add a one-line comment next to the entry
  pointing at the extension that uses it.

The build strips third-party JAR signatures (`META-INF/*.SF` / `*.DSA` /
`*.RSA`) so the merged archive loads without `SignatureException`.

Useful standalone Gradle tasks:

- `./gradlew validateExtensionJars` — verify the two JARs without compiling.
- `./gradlew extractExtensionReadmes` — refresh the extracted README files.
- `./gradlew test` — run the full test suite (Java + Ruby); see the
  **Testing** sections below for details.

## Installing the extension to OPA

1. Copy the extension JAR into the agent's **`lib_ext/`** directory
   (the conventional drop location, pre-created in every agent dist).
2. Make sure `server.classpath` in `conf/config.yml` points at the same
   directory:

   ```yml
   server:
     classpath: /opt/workato/agent/lib_ext
   ```

3. Register the extension. The exact YAML depends on the model (see
   **Two extension models** above).

   **Per-profile** — one `extensions.{profile}` block per profile, with
   `controller:` giving the class FQCN and sibling keys supplying
   configuration values:

   ```yml
   extensions:
     security:
       controller: com.mycompany.onprem.security.SecurityExtension
       signing-secret: HA63A3043AMMMM
   ```

   **Cloud-managed** — no mandatory block. Add
   `agent-config-support.{adapter}` only if you have static per-adapter
   values to pin:

   ```yml
   agent-config-support:
     configuration_sample:
       environment: prod   # optional, admin-pinned
   ```

   The agent finds the class via its `@AgentConfigSupport(name = "…")`
   annotation at startup — no FQCN in YAML. Per-connection values arrive
   from the Workato cloud without any admin action.

4. **Restart the agent.** The extension classloader is built once at
   startup; a new or updated JAR is not picked up until the agent is
   restarted. The same applies to any edit of `extensions:` or
   `agent-config-support:` — the agent reads the file once.

5. **Verify.** `GET http://<agent>/health` lists every registered
   extension. Your extension should appear there with
   `status: "enabled"` before you expect any request to work.

## Java extension tests

Unit tests for the on-prem extensions live under
`src/test/java/com/mycompany/onprem/`. They run against the **mock Extension
API** shipped as `extension-test-<version>.jar` — no running agent, no
real HTTP server, nothing that mimics the agent's own packaging. The
authoritative reference for the mock harness itself is
[`extension-api/README-extension-test.md`](extension-api/README-extension-test.md)
(generated on every build). The section below is a quick practical overview;
anything that contradicts the generated reference, trust the reference.

### Running

| Command                                                                                                                   | What runs                                                           |
|---------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------|
| `./gradlew test`                                                                                                          | Java JUnit **and** Ruby RSpec suites (per-test output for both).    |
| `./gradlew :test`                                                                                                         | Java only.                                                          |
| `./gradlew :test --tests '*Ntlm*'`                                                                                        | Only tests whose fully-qualified name matches the pattern.          |
| `./gradlew :test --tests 'com.mycompany.onprem.security.SecurityExtensionTest.computesExpectedSha256OfSecretThenPayload'` | One specific test method.                                           |
| `./gradlew :test --rerun-tasks`                                                                                           | Force re-run even if nothing changed (useful when a test is flaky). |

Per-test output (`PASSED`/`FAILED`/`SKIPPED`) is enabled via `testLogging` in
`build.gradle`.

### Writing a new test

Pattern — one `AgentExtensionHarness` per profile. Prefer a `static` field so
the harness starts once per class:

```java
import com.workato.agent.extension.test.AgentExtensionHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class MyExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
            .yaml("extensions/my-profile.yml")          // or .controller(MyExtension.class)
            .property("some-flag", "true");             // inline overrides win over YAML

    @Test
    void doesTheThing() throws Exception {
        MvcResult result = agent.mockMvc()
                .perform(post(agent.urlFor("/op"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":1}"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        // ... parse body and assert
    }
}
```

Rules of thumb:

- **Never hand-roll a mock of the Extension API.** Use
  `AgentExtensionHarness`. The harness provides the real
  `AgentExtensionApiV1`, honours `@Value("${...}")` and
  `getProfileConfiguration()` with the same semantics as the agent, and
  mounts the controller behind `/ext/{profile}/*` so request paths match
  production.
- **YAML fixtures** live under `src/test/resources/extensions/`. Prefer them
  when the configuration is non-trivial; fall back to `.property(k, v)` for
  one-value overrides inside a single test.
- **Real HTTP** in a test is fine when it's a tiny in-process server. See
  `NtlmExtensionTest` for the pattern: `com.sun.net.httpserver.HttpServer` on
  `127.0.0.1:0`, started in `@BeforeEach`, stopped in `@AfterEach`, with
  `AtomicInteger` counters on each handler to assert how many times a URL was
  hit (so you can verify, e.g., that malformed input skipped the HTTP call
  entirely).
- **Error paths** that throw before Spring MVC can wrap them (e.g.,
  `IllegalStateException` from a controller) surface as
  `mockMvc().perform(...)` throwing. Catch it with
  `assertThrows(Throwable.class, () -> …)` and walk the cause chain —
  `SecurityExtensionTest.missingSecretRaisesIllegalStateCarryingProfileName`
  shows the pattern.

### Troubleshooting

| Symptom                                                                | Likely cause                                                                                                                                                  |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `404` on a path that clearly matches                                   | You hit the raw servlet path (`/op`) instead of the prefixed URL. Use `agent.urlFor("/op")`.                                                                  |
| `NoSuchBeanDefinitionException` for a service the controller autowires | The harness context does not scan — register the dependency with `.bean(name, instance)` or `.configuration(Config.class)`.                                   |
| Test method reference doesn't resolve a route                          | Fall back to the explicit `post(agent.urlFor("/path"))` form (see `README-extension-test.md` on the method-reference resolver).                               |
| Shared harness state bleeds between tests                              | A `@RegisterExtension static` harness is reused across all tests in the class. Make sure handlers / counters are per-instance (`@BeforeEach`) and not static. |

See `extension-api/README-extension-test.md` for the complete harness DSL,
parent-context inheritance, non-JUnit framework hooks, and the full
troubleshooting table.

## Ruby custom-connector tests

Each on-prem extension has a companion Workato custom connector (Ruby) under
`src/main/ruby/<name>/connector.rb` — the deliverable that gets published to
Workato. The test harness around each connector lives under
`src/test/ruby/<name>/` as a **self-contained Workato SDK project**, so each
connector can be exercised without a running agent and without a Workato
cloud account.

```
src/main/ruby/
├── security/connector.rb      ← one connector.rb per extension (filename is fixed by the SDK)
├── ntlm/connector.rb
└── timer/connector.rb

src/test/ruby/
├── Gemfile                    ← shared — pulls in workato-connector-sdk + rspec + vcr + webmock
├── Gemfile.lock               ← shared — one `bundle install` serves every suite
├── spec_helper.rb             ← shared — VCR + RSpec config
├── security/                  ← test harness for one connector
│   ├── .rspec
│   ├── settings.yaml          ← connection fields the specs load as settings
│   └── spec/
│       ├── spec_helper.rb     ← one-liner: `require_relative '../../spec_helper'`
│       └── connector_spec.rb  ← CONNECTOR_PATH points at ../../../../main/ruby/<name>/connector.rb
├── ntlm/                      ← same layout
└── timer/                     ← same layout

src/test/resources/tape_library/
├── security/                  ← VCR cassettes per connector (one YAML per HTTP interaction set)
├── ntlm/
└── timer/
```

Bundler walks up from any connector subdir to the shared `Gemfile`, so each
subdirectory still behaves like a standalone SDK project (`cd src/test/ruby/<name>
&& bundle exec rspec` works unchanged). The shared `spec_helper.rb` resolves
the per-connector cassette dir by matching the CWD basename to the subdir
under `src/test/resources/tape_library/`.

### Running

| Command                                                                  | What runs                                                            |
|--------------------------------------------------------------------------|----------------------------------------------------------------------|
| `./gradlew test`                                                         | Java JUnit **and** every Ruby RSpec suite.                           |
| `./gradlew testRubyConnectors`                                           | Every Ruby suite (no Java).                                          |
| `./gradlew :<name>Rspec`                                                 | One Ruby suite — e.g. `:securityRspec`, `:ntlmRspec`, `:timerRspec`. |
| `./gradlew :<name>Rspec -Pspec='posts the payload'`                      | One example by description substring (forwarded as `-e`).            |
| `./gradlew :<name>Rspec -Pspec=spec/connector_spec.rb:12`                | One example by file:line (path containing `/` or `:` wins).          |
| `cd src/test/ruby/<name> && bundle exec rspec [spec_path[:line]] [-e …]` | Fastest local iteration — skips Gradle, full rspec CLI.              |

Prerequisites: Ruby 3.2.x (pinned in `.tool-versions` for asdf; works with any
version manager or system Ruby that satisfies it) and `bundler`. The Gradle
tasks locate `bundle` via `$BUNDLE_BIN` → `~/.asdf/shims/bundle` → `bundle` on
PATH, in that order. On CI runners that provision Ruby differently
(e.g., `ruby/setup-ruby`), set `BUNDLE_BIN` if the default lookup picks the
wrong interpreter.

### Running a spec from IntelliJ IDEA

**With the JetBrains Ruby plugin (Ultimate or RubyMine).** Point
*Preferences → Languages & Frameworks → Ruby SDK and Gems* at
`~/.asdf/installs/ruby/3.4.5/bin/ruby`, enable **Use bundler** under
*Ruby → RSpec*, and in the RSpec run-configuration template set
**Working directory** to the per-connector subdir (`src/test/ruby/<name>`) —
otherwise VCR will look for cassettes relative to the wrong CWD. Afterwards
the gutter triangles on `describe` / `it` run that example through
`bundle exec rspec` with the correct working dir.

**Without the Ruby plugin (IDEA Community).** Use the shared `-Pspec`
forwarding we wired into Gradle via a Gradle run configuration. The IDE
delegates to our existing `:<name>Rspec` task, which already knows the right
`workingDir`, bundle, and env:

1. **Run → Edit Configurations → + → Gradle.**
2. **Tasks:** `:<name>Rspec` — one of `:securityRspec`, `:ntlmRspec`, `:timerRspec`.
3. **Arguments** — pick one:
    - *whole file:* `-Pspec=spec/connector_spec.rb`
    - *single example by `:line`:* `-Pspec=spec/connector_spec.rb:12`
    - *single example by description:* `-Pspec='posts the payload'`
    - *whole suite:* leave empty.
4. Save the config (one per spec file / view you want quick access to).

Convention: each `connector_spec.rb` has a header comment with the exact
commands for both runners — copy-paste into the IDE run config.

### Writing a new spec

```ruby
CONNECTOR_PATH = File.expand_path('../../../../main/ruby/<name>/connector.rb', __dir__).freeze

RSpec.describe 'onprem <name> connector' do
  let(:settings) { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file(CONNECTOR_PATH, settings) }

  describe 'actions.<action_name>' do
    it 'does the thing' do
      VCR.use_cassette('<cassette_name>') do
        output = connector.actions.< action_name >.execute(settings, 'field' => 'value')
        expect(output['something']).to eq(...)
      end
    end
  end

  describe 'methods.<method_name>' do
    it 'returns the expected value' do
      expect(connector.methods.< method_name > (settings)).to eq(...)
    end
  end
end
```

Rules of thumb:

- Any HTTP the connector performs **must** be wrapped in `VCR.use_cassette`.
  `spec_helper.rb` is configured with `record: :none`, so an unmocked request
  fails loudly rather than silently reaching the network.
- Pure Ruby helpers (`methods.*` that don't do HTTP) can be asserted directly
  without a cassette.
- `settings.merge('key' => 'value')` to override one field for a single spec
  without editing `settings.yaml`.

### Working with VCR cassettes

Cassettes live in `src/test/resources/tape_library/<name>/`. Each file is a plain
YAML dump of one or more HTTP interactions. The default VCR matcher is
`[:method, :uri]` — request body and headers are **not** compared, so you can
change what the connector sends without invalidating a cassette, as long as
the method and URL stay the same.

#### Hand-editing (default workflow)

Because the agent is a Java process that the Ruby suite never contacts, our
cassettes are **synthetic** — authored by hand to describe what we expect the
agent to answer. Pick an existing file as a template and edit the response
body / status:

```yaml
---
http_interactions:
  - request:
      method: post
      uri: http://localhost/ext/<profile>/<path>
      body: { encoding: UTF-8, string: '' }   # ignored by default matcher
      headers: { }
    response:
      status: { code: 200, message: OK }
      headers:
        Content-Type: [ application/json ]
      body:
        encoding: UTF-8
        string: '{"signature":"..."}'
      http_version: '1.1'
    recorded_at: Thu, 15 Oct 2026 10:00:00 GMT
recorded_with: VCR 6.3.1
```

#### Re-recording against a live endpoint

The cassettes currently in the repo were authored by hand. If you want VCR to
capture a real response instead — because the agent-side contract changed, or
because you'd rather exercise the real stack once than hand-maintain the YAML
— the workflow below runs a stand-alone agent (not attached to the Workato
cloud gateway) and points VCR at it.

Record modes used below:

- `:none` (default here) — never record. Fail if no matching cassette.
- `:once` — record if cassette is missing, otherwise replay.
- `:new_episodes` — replay existing interactions, record anything new.
- `:all` — always record, overwriting.

##### 1. Install the agent

Follow the official install instructions for your platform —
[Linux (DEB)](https://docs.workato.com/en/on-prem/groups/add-agent/linux-deb),
[Linux (RPM)](https://docs.workato.com/on-prem/groups/add-agent/linux-rpm.html),
[macOS](https://docs.workato.com/en/on-prem/groups/add-agent/macos.html),
or [Windows](https://docs.workato.com/en/on-prem/groups/add-agent/windows).
We'll refer to the install root as `<AGENT_HOME>`.

##### 2. Install the extension JAR

Build and drop it into the agent's extension directory:

```sh
./gradlew jar
mkdir -p <AGENT_HOME>/ext
cp build/libs/opa-extensions-*.jar <AGENT_HOME>/ext/
```

##### 3. Configure the agent profiles

Edit `<AGENT_HOME>/conf/config.yml` so the agent knows where the JAR is and
which extensions to mount. The profile names must match the Ruby
`settings.yaml` `profile:` field (`security` / `ntlm`).

```yml
server:
  classpath: <AGENT_HOME>/lib_ext
extensions:
  security:
    controller: com.mycompany.onprem.security.SecurityExtension
    signing-secret: a-local-test-secret
  ntlm:
    controller: com.mycompany.onprem.ntlm.NtlmExtension
    # connect-timeout-ms / read-timeout-ms can be set here too
```

##### 4. Start the agent in stand-alone mode

Started without attaching to the Workato gateway, the agent exposes the
extension HTTP endpoints on a local address you choose via the
`--connector-listen` option. The exact flag name has not been verified
against public Workato docs — if `run.sh --help` on your version shows a
different spelling, use that.

```sh
# Example — adjust to your platform's run script and desired port
<AGENT_HOME>/bin/run.sh --connector-listen=http://127.0.0.1:8080
```

(On Linux/systemd service installs the start command is
`sudo systemctl start workato-agent.service`; see
[Run an on-prem agent](https://docs.workato.com/on-prem/agents/run.html) for
the per-platform specifics.)

Quick sanity check — the agent should answer the mounted route:

```sh
curl -XPOST -H 'Content-Type: application/json' \
     -d '{"payload":"hello"}' \
     http://127.0.0.1:8080/ext/security/computeDigest
# → {"signature":"..."}
```

##### 5. Align the connector URL with the live agent

The connectors in this repo hard-code `http://localhost/...` (which is the
URL shape Workato's secure tunnel expects in production). For local
recording, those URLs must resolve to the port the agent listens on. Two
options:

- **Port 80:** start the agent with `--connector-listen=http://127.0.0.1:80`
  (needs root/`sudo` on macOS/Linux). Then the hard-coded URLs just work.
- **Any other port:** add a `base_url` field to the connector connection and
  reference it from each `post(...)` call, e.g.
  `post("#{connection['base_url']}/ext/#{connection['profile']}/computeDigest", ...)`
  with `base_url: http://127.0.0.1:8080` added to `settings.yaml` for the
  recording session. Revert the production URL back to `http://localhost/...`
  (or keep `base_url` with a `'http://localhost'` default) before shipping —
  the Workato runtime relies on literal `localhost` to route through the
  tunnel.

##### 6. Record

Delete the stale cassette, flip the record mode, run the one spec:

```sh
rm src/test/resources/tape_library/<name>/<cassette_name>.yml
```

```ruby
# src/test/ruby/<name>/spec/spec_helper.rb — temporarily
c.default_cassette_options = { record: :once } # was :none
```

```sh
cd src/test/ruby/<name>
bundle exec rspec spec/connector_spec.rb -e 'the one example'
```

VCR writes `src/test/resources/tape_library/<name>/<cassette_name>.yml` from whatever the agent
returned. Future runs replay it.

##### 7. Clean up before committing

- **Revert `record: :none`** in `spec_helper.rb`. Leaving `:once` or
  `:new_episodes` in the shared helper lets tests silently reach the network
  on CI.
- **Revert the connector URL** back to the production `http://localhost/...`
  form (or remove the test-only `base_url` from `settings.yaml`).
- **Review the cassette.** VCR captures all headers verbatim, including any
  session/trace tokens the agent happens to emit — scrub what you don't want
  checked in.
- **Run `./gradlew test`** to verify replay works with the new cassette.

If you don't have a live agent, hand-editing the YAML (preceding section)
is the supported workflow and what the cassettes in this repo were produced
with.

#### Updating one entry inside a cassette

For a small change (e.g., a different signature in the response) it's usually
faster to edit the YAML directly than to re-record. Just change the `body`
field — the next test run picks it up.

### Troubleshooting

| Symptom                                                                                                      | Likely cause                                                                                                                                                                                                               |
|--------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `WebMock::NetConnectNotAllowedError`                                                                         | Spec hits a URL not covered by any cassette. Wrap the call in `VCR.use_cassette`, or add a cassette whose request `method`+`uri` matches.                                                                                  |
| `VCR::Errors::UnhandledHTTPRequestError: An HTTP request has been made that VCR does not know how to handle` | Same as above — no cassette matches the request.                                                                                                                                                                           |
| `Could not find 'bundler' (X.Y.Z) required by your Gemfile.lock`                                             | Gradle is calling the wrong `bundle` (system Ruby vs. project Ruby). Set `BUNDLE_BIN` to the right executable, or run `cd src/test/ruby/<name> && bundle install` once to let your Ruby manager pick up the right version. |
| Tests pass locally but fail in CI                                                                            | Different Ruby version. Check `.tool-versions` is honoured and the CI gemset matches.                                                                                                                                      |

## Deploying the connector to Workato

Each `src/main/ruby/<name>/connector.rb` must be published to a Workato
workspace before any recipe can use it. There are two supported paths — the
Workato UI (manual, no gem needed) and the SDK CLI (`workato push`, scriptable
and the only option for CI).

### Option 1 — Paste into the Workato UI

Fastest for a one-off or when iterating interactively. No gem required —
everything happens in the browser.

#### Create the connector (first-time only)

1. In your Workato workspace open **Tools → Connector SDK**.
2. Click the **New connector** button (top-right of the Connector SDK home).
3. Choose a starting point — the current options documented by Workato are
   **Get guided from a Workato template** or **Import OpenAPI specification**.
   Pick the template path (OpenAPI only applies to REST APIs with a public
   spec, which doesn't fit the agent proxy case). You'll replace the template
   code wholesale in the next step.
4. Enter a title (e.g. *On-prem security (SHA-256)*), description, and logo,
   then click **Go to editor**.

#### Paste the connector source

5. In the editor, open the **Source code** tab (the CodeMirror-based editor —
   you'll see the template Ruby there).
6. Select all of it and replace with the full contents of
   `src/main/ruby/<name>/connector.rb` from this repository.
7. Save the draft with **⌘-S** (macOS) / **Ctrl-S** (Windows/Linux).
   Save-as-draft does not yet make the version usable in recipes — it's a
   working copy only.

#### Smoke-test from the editor

8. Open the **Connection** panel (right-hand side of the editor), fill in the
   connection fields (for the security connector that's just `profile`; for
   NTLM it's `profile` + credentials) and click **Connect**. This runs the
   `test:` lambda against your agent. A green tick means the agent answered.
9. Open the **Test code** / **Debugger** tab, pick an action
   (`sha256_digest` / `get`), supply a sample input, and run it. The response
   from the live agent is shown in the panel.

#### Release

10. When the draft behaves as expected, click **Release latest version**
    (top-right). Only released versions are visible to recipes.
11. In the recipe editor, add a step → search for your connector by title →
    pick an action.

Subsequent updates: edit the Source code → **Save** → smoke-test → **Release
latest version**. The UI keeps a version history; previous releases stay
active until superseded, so you can roll back by re-releasing an older
version from the version list.

> UI label caveat: Workato tweaks the editor layout from time to time. If a
> button name below doesn't match what you see, the authoritative reference
> is [Using the Workato Connector SDK](https://docs.workato.com/developing-connectors/sdk/quickstart/quickstart.html)
> — the overall flow (new connector → paste → save → release → use in recipe)
> is stable even when labels drift.

### Option 2 — Push from the CLI (`workato push`)

Scriptable and the only option if you want publishing to happen as part of a
CI pipeline. Requires the same `workato-connector-sdk` gem the tests use
(declared in the shared `src/test/ruby/Gemfile`) and a workspace API token.

#### 2.1. Create an API client in Workato

In the workspace open **Workspace admin → API clients → + Create API client**.
Grant it the **Get details** permission on the Developer API (minimum required
by `workato push`); scope the client to your sandbox folder if you have one.
Copy the token immediately — you cannot retrieve it later.

#### 2.2. Push

The `Gemfile` + lockfile that pull in `workato-connector-sdk` live under
`src/test/ruby/<name>/` (same SDK project the specs use), while `connector.rb`
is under `src/main/ruby/<name>/`. Run `bundle exec` from the test directory
and point `--connector` at the deliverable:

```sh
cd src/test/ruby/<name>
WORKATO_API_TOKEN='…' bundle exec workato push \
    --connector ../../../main/ruby/<name>/connector.rb \
    --api-token "$WORKATO_API_TOKEN" \
    --environment https://app.workato.com \
    --notes "release from $(git rev-parse --short HEAD)"
```

- `--environment` must match your workspace's data center
  (`https://app.workato.com`, `https://app.eu.workato.com`,
  `https://app.au.workato.com`, …).
- `--notes` becomes the version description visible in the UI's version list.
- `--folder <ID>` optionally targets a specific workspace folder.
- `--title`, `--description` (path to README), `--logo` (path to PNG/JPG)
  override the title / description / logo of the published connector.

First push creates the connector; subsequent pushes add a new version — the
previously-released version stays active until you release the new one from
the UI (or via the Workato Platform API).

#### 2.3. Suggested Gradle wiring (optional)

If you want publishing in the same build, add Exec tasks mirroring the test
ones — they live in `src/test/ruby/<name>` (the Gemfile home) and point at
the connector under `src/main/`:

```groovy
task securityPush(type: Exec) {
    group = 'publishing'
    workingDir file('src/test/ruby/security')
    commandLine bundleBin(), 'exec', 'workato', 'push',
            '--connector', '../../../main/ruby/security/connector.rb',
            '--api-token', System.getenv('WORKATO_API_TOKEN'),
            '--environment', System.getenv('WORKATO_BASE_URL') ?: 'https://app.workato.com',
            '--notes', "release from ${project.version}"
}
```

Do **not** hook this into `check` — it has external side effects.

### Verifying after deployment

- In the recipe editor the custom connector appears under
  **+ App → <your connector title>**.
- Open a connection, set `profile` to a profile the agent actually has in
  `config.yml`, click **Connect**. The connection test lambda runs the same
  `post('…/computeDigest' | '…/request')` the specs exercise.
- If the test fails with HTTP 404, the extension isn't mounted on the agent
  (check the agent log or `agent.getContext().getProfileName()` returns null).
- If it fails with `Workato::Connector::Sdk::...`-shaped errors, the
  connector Ruby code is at fault; reproduce locally with
  `bundle exec rspec` first.

## References

Workato documentation used while building and testing this project. Prefer
the generated READMEs under `extension-api/` for the Java-side API/mock
contracts (they are version-matched to the JARs on your classpath); the links
below cover the surrounding Workato tooling.

### On-prem agent

- [On-prem agent overview](https://docs.workato.com/on-prem/agents.html)
- [Run an on-prem agent](https://docs.workato.com/on-prem/agents/run.html) — platform-specific start/stop commands.
- [Monitoring (`--mgmt-listen`)](https://docs.workato.com/on-prem/agents/monitoring.html)
- [Extensions overview](https://docs.workato.com/on-prem/agents/extension.html) — the production docs for the extension
  model.
- Install guides:
  [Linux DEB](https://docs.workato.com/en/on-prem/groups/add-agent/linux-deb),
  [Linux RPM](https://docs.workato.com/on-prem/groups/add-agent/linux-rpm.html),
  [macOS](https://docs.workato.com/en/on-prem/groups/add-agent/macos.html),
  [Windows](https://docs.workato.com/en/on-prem/groups/add-agent/windows).

### Workato Connector SDK (Ruby)

- [`workato-connector-sdk` on GitHub](https://github.com/workato/workato-connector-sdk) — gem source and README.
- [SDK CLI — Getting started](https://docs.workato.com/developing-connectors/sdk/cli/guides/getting-started.html) —
  install, `workato new`, `workato exec`, running tests.
- [SDK CLI — Commands reference](https://docs.workato.com/en/developing-connectors/sdk/cli/reference/cli-commands.html) —
  full flag reference for `workato push`, `workato edit`, `workato oauth2`.
- [SDK CLI — Project directory reference](https://docs.workato.com/developing-connectors/sdk/cli/reference/cli-project-directory-reference.html) —
  the layout Workato expects (`connector.rb`, `settings.yaml`, `spec/`, `tape_library/`).
- [SDK CLI — RSpec guide (
  `connector_spec`)](https://docs.workato.com/developing-connectors/sdk/cli/guides/rspec/connector_spec.html) — test
  patterns, `Connector.from_file`, VCR integration.
- [SDK CLI — RSpec reference](https://docs.workato.com/en/developing-connectors/sdk/cli/reference/rspec-commands.html).

### Workato Platform API

- [Custom connectors API](https://docs.workato.com/workato-api/custom_connectors.html) — if you prefer to publish via
  HTTP instead of `workato push`.

## Using an extension in a recipe

Each extension ships with a companion Workato custom connector under
`src/main/ruby/<name>/connector.rb`. The connector is what the recipe talks
to; the extension is what the connector talks to. For the per-connector
contract (actions, triggers, connection fields), open the matching
per-extension README linked in the **Available extensions** table above.

To publish and wire up a connector in a Workato workspace, see
**Deploying the connector to Workato** earlier in this document.
