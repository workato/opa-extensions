# Agent Extensions Test Harness — Customer Guide

> If you are reading this to **test an extension** for Workato On-Premise Agent
> (the agent), you are in the right place. For the production API your extension
> compiles against, see `extension-api/README.md`. For the agent maintainers, see
> `CLAUDE.md` in this directory.

## What is it?

Two complementary test-time tools for agent extensions:

- **`AgentExtensionHarness`** (the subject of most of this guide) — a
  Spring-MVC + `MockMvc` harness that lets you **integration-test your
  controller without the agent installed**. Fast, no I/O, pins the Spring
  wiring and the `/ext/{profile-name}/*` routing contract. 95% of the time
  this is what you want. The entire "Quick start" and "Configuration access"
  sections below are about `AgentExtensionHarness`.
- **`DeployedAgentLauncher`** — launches the **real agent in-process** against a
  deployed distribution on disk. Use this when you need to verify behaviour
  that the mock harness cannot cover — classloader isolation from `lib_ext/`,
  real Jetty binding, or an end-to-end mTLS tunnel to the Workato cloud. See
  the dedicated section "Running against the real agent — `DeployedAgentLauncher`"
  below.

### What `AgentExtensionHarness` covers without starting the agent

- `@Autowired AgentExtensionApiV1` into your controller
- `@Value("${key}")` injection with the same direct-lookup semantics as the agent
  (dots in keys are literal, not nesting separators)
- Request routing to `/ext/{profile-name}/*`
- `@PathVariable`, `@RequestBody`, Spring MVC content negotiation
- Service beans and `@ControllerAdvice` your controller depends on (register
  them with `.bean(...)` / `.configuration(...)` — nothing is auto-scanned)
- Unmodifiable-view contract of `AgentExtensionContextV1.getProfileConfiguration()`

Out of scope for the harness: Jetty-specific HTTP behaviour (buffering,
chunking, timeouts), the agent process lifecycle, classloader isolation —
these are the cases `DeployedAgentLauncher` exists for.

## Quick start

### 1. Add the harness as a test dependency

```gradle
dependencies {
    compileOnly("com.workato.build:extension-api")       // production API
    testImplementation("com.workato.build:extension-test") // this module
}
```

The harness transitively pulls in Spring Web MVC, Spring Test, Jackson,
JUnit 5, and the servlet API — the full MockMvc toolchain.

### 2. Declare the extension in a YAML fixture

Place the fixture under `src/test/resources/extensions/` — same shape as the agent's
production `config.yml`:

```yaml
# src/test/resources/extensions/my-profile.yml
extensions:
  my-profile:
    controller: com.example.MyController
    api.endpoint: https://internal.example.com
    api.timeout-ms: 5000
```

The harness pulls everything under `extensions.my-profile.*` into the
extension's property view — identical to how the agent parses `config.yml` at
startup. The `controller:` key (or the alias `controllerClass:`) is also
honoured — if you do not call `.controller(...)` explicitly, the harness loads
the class named in YAML and registers it, matching production's
`ExtensionConfigParser` behaviour. Call `.controller(X.class)` only when you
want to override the YAML (e.g., a test-only subclass) or when the controller
needs a non-default constructor — see "Controllers that depend on other beans"
below.

### 3. Write the test

```java
import com.workato.agent.extension.test.AgentExtensionHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class MyControllerTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
            .yaml("extensions/my-profile.yml");       // controller class is in the YAML

    @Test
    void endpointAnswers() throws Exception {
        var result = agent.mockMvc()
                .perform(agent.request(MyController::op))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("expected body", result.getResponse().getContentAsString());
    }
}
```

### Method references instead of route strings

`agent.request(Controller::method)` reads the method's `@GetMapping`,
`@PostMapping` (etc.) at runtime and builds the full MockMvc request —
HTTP method and URL (`/ext/{profile}/...`) in one go. No need to repeat
the path string already declared on the controller.

For methods with `@PathVariable`, pass the values positionally:

```java
// controller: @GetMapping("/items/{id}") Item get(@PathVariable String id, ...)
mockMvc().

perform(agent.request(MyController::get, 42));
// → GET /ext/my-profile/items/42
```

Non-path parameters (`@RequestBody`, `@RequestParam`, headers) are skipped
by the resolver — supply only path-variable values via `pathVars`. Body /
headers go through the returned builder in the usual way:

```java
mockMvc().

perform(agent.request(MyController::submit)
        .

contentType(MediaType.APPLICATION_JSON)
        .

content("""{"x":1}"""));
```

**Limits of the method-reference form.** Falls back to `agent.urlFor("/op")` /
`get(agent.urlFor("/op"))` in these cases:

- The controller has two methods with the same name — overloaded methods throw
  `IllegalStateException` because the `SerializedLambda` does not disambiguate
  the signature.
- The method takes two or more arguments total — only `Ref0` (zero args) and
  `Ref1` (one arg) overloads exist. Methods with multiple parameters compile
  only against `Ref1` and need a single-arg form; otherwise use the string form.
- The route has `@PathVariable` parameters without explicit `value=` / `name=`
  — then the harness falls back to the reflective parameter name, which
  requires the controller to be compiled with the `-parameters` javac flag.
  Without `-parameters` the parameter arrives as `arg0` and substitution
  silently no-ops. Prefer `@PathVariable("id")` to avoid depending on compile
  flags.

Path-variable values are URL-encoded (`URLEncoder.UTF_8`) before substitution,
so values containing `/` or `?` are percent-encoded.

The `@RegisterExtension static` field integrates with JUnit 5 — the harness
starts before the test class and stops after. If you use `@Nested` classes
(where static fields are not allowed), use a non-static field instead; the
harness detects that and starts/stops per test.

**Inline properties.** Prefer YAML — one source of truth for production and
tests. If you need to override a single value for a specific test or set
something not worth putting in the fixture, use `.property(k, v)`; inline
properties take precedence over YAML regardless of call order.

### Controllers that depend on other beans

If your controller autowires services, provide them with `.bean(name, instance)`
or `.configuration(Config.class)`:

```java

@RegisterExtension
static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
        .controller(MyController.class)
        .bean("httpClient", stubHttpClient)             // singleton bean
        .configuration(MyTestConfig.class)              // @Bean methods
        .property("api.endpoint", "https://x");
```

`controller(Class)` lets Spring instantiate the controller and autowire its
dependencies (constructor-injection, field-`@Autowired`, and `@Value` all
work). If you prefer to assemble the controller yourself — e.g., Mockito
`@InjectMocks` or a constructor you want to call directly — pass the instance
instead:

```java
MyController ctrl = new MyController(mockHttpClient);
ctrl.someField =...;
        AgentExtensionHarness.

extension("my-profile").

controller(ctrl);
```

In that case Spring does *not* autowire the instance — only the
`"agentExtensionApi"` bean is still exposed via the harness context so you can
assert against it or set it manually.

### Reusing an existing Spring context — `.parentContext(...)`

If your test already builds an `ApplicationContext` (via `@SpringBootTest`,
`@ContextConfiguration`, or by hand), you can hand it to the harness as the
parent of its per-endpoint context. Every bean in the parent — `@Component`s,
`@Service`s, test stubs — becomes autowire-visible to your controller,
with no need to re-register anything through `.bean(...)` /
`.configuration(...)`.

```java

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CustomerBeansConfig.class)
class MyControllerTest {

    @Autowired
    ApplicationContext parent;

    AgentExtensionHarness agent;

    @BeforeEach
    void setup() {
        agent = AgentExtensionHarness.extension("my-profile")
                .controller(MyController.class)
                .parentContext(parent)              // ← inherit every bean
                .yaml("extensions/my-profile.yml")
                .start();
    }

    @AfterEach
    void tearDown() {
        agent.stop();
    }
}
```

**Precedence.** Child beans (your controller, `"agentExtensionApi"`, and
anything added via `.bean(...)` / `.configuration(...)`) shadow same-named
parent beans. Standard Spring child-context semantics.

### Non-JUnit test frameworks

Call `start()` / `stop()` explicitly from whatever lifecycle hook your
framework provides (TestNG `@BeforeClass`, Spock `setupSpec`, etc.):

```java
static AgentExtensionHarness agent;

@BeforeAll
static void setup() {
    agent = AgentExtensionHarness.extension("my-profile")
            .controller(MyController.class)
            .property("api.endpoint", "https://x")
            .start();
}

@AfterAll
static void tearDown() {
    agent.stop();
}
```

## URL layout

The harness mounts your controller as the agent does — behind the servlet mapping
`/ext/{profile-name}/*`. Build request URLs with `agent.urlFor(path)`, which
prepends the production prefix:

| Controller annotation        | `agent.urlFor("/op")`      | What Spring MVC matches |
|------------------------------|----------------------------|-------------------------|
| `@RequestMapping("/op")`     | `/ext/my-profile/op`       | `/op`                   |
| `@GetMapping("/items/{id}")` | `/ext/my-profile/items/42` | `/items/{id}`           |

The full request URI matches the shape production sees, so bugs around
absolute URLs the controller emits (e.g., `Location` headers that forget the
`/ext/{name}` prefix) are catchable.

## Configuration access

### Via `@Value("${key}")`

Any key passed to `.property(k, v)` is resolvable with `@Value`:

```java

@Value("${api.endpoint}")
private String endpoint;

@Value("${api.timeout-ms:3000}")   // defaults supported
private int timeoutMs;
```

Dots are literal, not nesting separators — exactly like production
`EndpointPropertySource`. A key `mail.pop3.username` is looked up as that
literal string.

### Via `AgentExtensionApiV1`

```java

@Autowired
AgentExtensionApiV1 agent;

Map<String, Object> cfg = agent.getContext().getProfileConfiguration();
String name = agent.getContext().getProfileName();
```

`getProfileConfiguration()` returns the same unmodifiable view the production
implementation returns: `put`/`remove`/`clear`/`putAll` throw
`UnsupportedOperationException`. If your controller accidentally mutates the
configuration, the test fails here rather than silently drifting from
production behaviour.

## What the harness exposes

| Method                                  | What it returns                                                                                                                                |
|-----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| `mockMvc()`                             | Spring's `MockMvc` for issuing requests                                                                                                        |
| `agentExtensionApi()`                   | The `AgentExtensionApiV1` the controller sees — handy for direct assertions                                                                    |
| `webApplicationContext()`               | The underlying `AnnotationConfigWebApplicationContext` for advanced wiring                                                                     |
| `profileName()`                         | The configured profile name                                                                                                                    |
| `urlFor(path)`                          | The full request path including the `/ext/{profile}` prefix                                                                                    |
| `urlFor(Controller::method)`            | The full request path, resolved from the method's `@GetMapping` / `@PostMapping` / … (zero-arg controller methods)                             |
| `urlFor(Controller::method, pathVars)`  | Same, with `@PathVariable` placeholders substituted positionally (one-arg controller methods; see "Limits of the method-reference form" above) |
| `request(Controller::method)`           | A `MockHttpServletRequestBuilder` with HTTP method inferred from annotations; further `.contentType(...)` / `.content(...)` chained as usual   |
| `request(Controller::method, pathVars)` | Same, with path variables substituted                                                                                                          |

## Running against the real agent — `DeployedAgentLauncher`

> ⚠ **Not for CI — manual / repro use only.** Every `DeployedAgentLauncher`
> test needs a deployed and activated agent dist on disk (cert.pem /
> cert.key signed by Workato for a real workspace, matching gateway
> environment). That is per-machine state, not a reproducible CI artifact —
> keep these tests `@Disabled` by default and run individual methods manually
> from the IDE when verifying end-to-end behaviour or reproducing a
> mysterious issue that only shows up inside a real agent process. For
> anything your Spring MVC wiring can cover, use `AgentExtensionHarness`
> instead — it's fast, hermetic, and CI-safe.

`AgentExtensionHarness` is the right tool for 95% of extension tests — it is
fast, has no I/O, and pins the Spring MVC wiring that governs how your
controller behaves at runtime. `DeployedAgentLauncher` is a complementary tool for the
remaining 5%: cases where you need to verify behaviour that only the real
agent process exercises.

Pick `DeployedAgentLauncher` when you need to:

- Confirm the agent accepts your extension end-to-end — classloader isolation
  from `lib_ext/`, config parsing, dispatcher mounting, Jetty binding.
- See the extension answer **through the real Workato gateway tunnel** (full
  gateway mode).
- Debug your extension in-process while the full agent is running (IDE
  breakpoints fire inside both `agent-core` and your controller).
- Reproduce a bug that only surfaces inside the real agent process (TLS
  edge cases, classloader interactions with `lib_ext/`, startup-order
  dependencies) that `AgentExtensionHarness` cannot reach.

Skip it when you can — `AgentExtensionHarness` tests run in milliseconds, this
one takes seconds, depends on an on-disk agent distribution, and cannot run
in CI.

### Prerequisites

- A deployed agent distribution at a known filesystem path. The distribution
  must be **activated** regardless of run mode — `conf/cert.pem` +
  `conf/cert.key` must be present and signed by Workato for your workspace.
  The agent initialises its SSL context eagerly at startup, so without the
  cert pair you get `"Agent SSL context is incomplete"` even in the
  direct-local-HTTP mode where you never actually talk to the gateway.
- Your controller classes available on the test JVM classpath (the launcher
  reads their on-disk code source via `Class#getProtectionDomain` and feeds
  the URL to the agent's isolated classloader).
- One `@Test` method per JVM invocation — see **Isolation between tests**
  below. Running the whole class in a single JVM will fail the second test.

### Two mutually exclusive run modes

The agent decides which beans to initialise based on the presence of
`--connector-listen`. These modes cannot both be active in the same process:

| Mode                          | How to activate                                    | Local HTTP available?                   | Workato sees agent?                           |
|-------------------------------|----------------------------------------------------|-----------------------------------------|-----------------------------------------------|
| **Full gateway** (production) | do not call `openLocalAccess(...)`                 | no — `RunningAgent.port()` throws       | yes — outbound mTLS WebSocket to the gateway  |
| **Direct local HTTP access**  | call `openLocalAccess()` or `openLocalAccess(int)` | yes — `RunningAgent.httpGet / httpPost` | no — `AgentEnabled` suppresses outbound beans |

`openLocalAccess(...)` opens direct HTTP access to the agent from the test
JVM — convenient for in-process smoke tests. The trade-off is explicit: it
injects `--connector-listen`, which makes `@Conditional(AgentEnabled.class)`
on every outbound-gateway bean evaluate to false, so the agent **will not
connect to the Workato cloud gateway** (Workato will not see it as online).
Pick one of the two modes based on what you are verifying.

### Which dist layout do I need?

The `path` you hand to `create(profile, path)` can take three shapes. Match
your situation to one:

| Your situation                                                                                                                                                        | `path` points at                                              | Which jars run?                                                   |
|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|-------------------------------------------------------------------|
| **You received a dist** (download, tarball, Docker image contents) with its own `lib/` + `conf/`, and you want to verify your extension on it as-is.                  | the dist root                                                 | the dist's own jars                                               |
| **You only have activation certs** (and optionally a loose `workato.properties` / `root_ca_*.pem` bundle), and want to run the extension against your current source. | a loose conf directory (just `conf/`, no `lib/`)              | the locally-built agent — `./gradlew :agent-launcher:installDist` |
| **You received a dist** but want to retarget it at a different gateway environment (preview vs prod, dev-env, …) without rebuilding its jars.                         | the dist root; drop a loose `workato.properties` into `conf/` | the dist's own jars (plus loose overrides for gateway / trust)    |

Shape of each on-disk layout:

```
# Shape A — full dist (deployed agent)
/opt/workato-agent/
    lib/
        agent-launcher-*.jar
        agent-core-*.jar
        …
    conf/
        cert.key, cert.pem                 # REQUIRED (activation)
        config.yml

# Shape B — loose conf only (triggers local-build fallback)
/opt/workato-conf/
    conf/
        cert.key, cert.pem                 # REQUIRED (activation)
        config.yml                         # optional
        workato.properties                 # REQUIRED here — supplies gateway target + trust CAs
        root_ca_*.pem                      # REQUIRED — PEMs named in workato.properties

# Shape C — full dist with loose overrides (point an existing dist at a different gateway)
/opt/workato-agent/
    lib/
        …
    conf/
        cert.key, cert.pem                 # REQUIRED (activation)
        config.yml
        workato.properties                 # loose override — wins over the jar-bundled copy
        root_ca_*.pem                      # PEMs named in workato.properties
```

For all three shapes, if `conf/workato.properties` is present the launcher
auto-injects `--workato-properties <abs path>` + `--cert /abs/*.pem|…`; see
**Overriding `workato.properties` and the gateway trust-store** below.

### Example — direct local HTTP access (in-process smoke test)

```java
import com.workato.agent.extension.test.DeployedAgentLauncher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.http.HttpResponse;

class MyControllerIntegrationTest {

    private static final Path DIST = Paths.get("/opt/workato-agent");

    @Test
    void echoAnswersOverLocalHttp() throws Exception {
        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create("my-profile", DIST)
                .controllerClass(MyController.class)
                .property("greeting", "hello")
                .openLocalAccess()                   // open direct local HTTP; disables gateway connection
                .start()) {

            HttpResponse<String> res = agent.httpGet(MyController::echo);

            assertEquals(200, res.statusCode());
        }
    }
}
```

The runner picks a free TCP port, injects `--connector-listen
http://127.0.0.1:{port}` into the launcher args, and exposes the port via
`RunningAgent.port()` — which throws `IllegalStateException` whenever
`openLocalAccess(...)` was not called. `httpGet` / `httpPost` take a controller
method reference and build the URL — host, port, `/ext/{profile}/`,
controller class-level `@RequestMapping`, method-level `@GetMapping` /
`@PostMapping` — from what is already declared on the controller.

### Example — full gateway mode (Workato visibility)

```java
import java.time.Duration;

@Test
void workatoSeesTheAgentAsOnline() throws Exception {
    try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create("my-profile", DIST)
            .controllerClass(MyController.class)
            .property("greeting", "hello")
            // No .openLocalAccess(...) — stay in full gateway mode.
            .start()) {

        String connectLine = agent.awaitGatewayConnected(Duration.ofSeconds(60));

        assertThat(connectLine).contains("Connected to gateway at");
        // At this point the agent appears online in the Workato cloud UI;
        // real recipes can trigger your controller through the gateway tunnel.
    }
}
```

`awaitGatewayConnected` tees `System.out` at `start()` time and scans the
captured buffer for the `"Connected to gateway"` INFO line that production
`run.sh` also emits when activation is healthy. Times out if the handshake
does not complete (bad cert, blocked egress, offline gateway).

### Local-build fallback — using Shape B

When you only have activation certs (no deployed `lib/`) and want the agent
to run against your current `agent-core` / `extension-api` source instead of
a packaged dist, run `./gradlew :agent-launcher:installDist` once; the
launcher auto-discovers the output under
`modules/agent-launcher/build/install/*` and uses those jars whenever
`path/lib/` is missing. If `path/lib/` is absent *and* no local build is on
disk, `create(...)` fails fast with `IllegalStateException` naming both
failure modes.

```java
try(DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(
                "my-profile",
                Paths.get("/opt/workato-conf"))        // Shape B — no lib/, triggers local-build fallback
        .controllerClass(MyController.class)
        .start()){
        agent.

awaitGatewayConnected(Duration.ofSeconds(60));
        }
```

### Overriding `workato.properties` and the gateway trust-store

By default the agent reads `workato.properties` once at startup from the
jar classpath, giving it the environment target (`agent.default.gateway`),
the embedded trust-CA names (`gateway.ca.file`), the version
(`agent.version`), and friends. A loose `path/conf/workato.properties` is
**not** read automatically by `Config.init`, so without help it is silently
ignored.

The launcher closes that gap in two layered ways whenever a loose
`path/conf/workato.properties` is present:

1. It appends `--workato-properties <abs path>` to the launcher args.
   `Launcher.start` feeds that path into `Config.overrideFromFile`, so every
   key in the loose file overrides the jar-embedded one — including
   `agent.version`, `agent.default.gateway`, and so on.
2. It resolves each PEM listed in `gateway.ca.file` as a sibling of
   `cert.pem` inside `path/conf/`, and appends
   `--cert /abs/path1.pem|/abs/path2.pem`. These files are the caller's own
   on-disk PEMs — not touched on `stop()`.

The agent trusts a specific set of Workato gateway CA certificates
(`root_ca_prod.pem`, `root_ca_preview.pem`, …) that are **bundled as
resources inside the agent jars**, not placed under `conf/`. The resource
names are listed in `workato.properties` under `gateway.ca.file` and differ
between environments:

```properties
# A prod agent build (sg3.workato.com, sg4.workato.com)
agent.default.gateway=sg3.workato.com|sg4.workato.com
gateway.ca.file=root_ca_prod.pem|root_ca-v20240503-10y-prod.pem
# A preview agent build (sg3.preview.workato.com, sg4.preview.workato.com)
agent.default.gateway=sg3.preview.workato.com|sg4.preview.workato.com
gateway.ca.file=root_ca_preview.pem|root_ca-v20240530-10y-preview.pem
```

Two situations make the jar-bundled trust-store the wrong one:

- **Local-build fallback** (Shape B): the local build's jars (usually prod)
  do not trust the server certificate chain of the gateway the external
  cert is bound to (usually not prod). TLS handshake fails with
  `"unable to find valid certification path to requested target"`.
- **Pointing a self-consistent dist at a different gateway environment**
  (preview, dev-env, …) without rebuilding its jars. Dropping
  `workato.properties` + `root_ca_*.pem` files into `conf/` is enough.

Expected layout for the loose-conf override:

```
path/                                  # the Path passed to create(...)
    conf/
        cert.key
        cert.pem
        config.yml                     # optional
        workato.properties             # gateway.ca.file=root_ca_preview.pem|…
        root_ca_preview.pem            # names match workato.properties
        root_ca-v2024…-preview.pem
```

If no loose `workato.properties` is present, the launcher does nothing —
the agent's own classloader picks up the trust-store from its own jars.
That is the correct behaviour for a self-consistent full dist (Shape A).

Pass `--cert /abs/path.pem` (or `--cert=/abs/path.pem`) through
`argument(...)` to opt out of the auto-injection and use your own trust
bundle instead.

### Loading an extension profile from YAML

Use `.yaml(classpathResource)` to load the baseline from the same YAML shape
production uses — the runner reads the `extensions.{profileName}` section
and merges it into the profile body:

```yaml
# src/test/resources/extensions/my-profile.yml
extensions:
  my-profile:
    controllerClass: com.example.MyController
    greeting: baseline-greeting
    api.endpoint: https://x
```

```java
DeployedAgentLauncher.create("my-profile",DIST)
        .

yaml("extensions/my-profile.yml")              // baseline from YAML
        .

property("greeting","overridden-for-test")    // single-key override
        .

openLocalAccess()
        .

start();
```

All four profile-populating methods merge rather than replace: `yaml(...)`,
`property(k, v)`, `properties(Map)`, `controllerClass(Class)` can be called in
any order, and later calls win on overlapping keys.

### Tweaking foreign profiles that live in the base `config.yml`

The runner's primary profile is the one passed to `create(name, dist)`. If the
agent's `conf/config.yml` already declares other extension profiles, you can
tweak individual keys without rewriting them wholesale:

```java
DeployedAgentLauncher.create("my-profile",DIST)
        .

controllerClass(MyController .class)
        .

extensionProperty("other-profile","api.endpoint","https://test")
        .

extensionProperties("other-profile",Map.of("timeout-ms", 1000))
        .

openLocalAccess()
        .

start();
```

`extensionProperty` / `extensionProperties` take the foreign profile name as
the first argument and merge into whatever the base `config.yml` already has
for it.

### Keeping the dist path out of version control

The `dist` argument of `create(...)` is mandatory and typically points at a
machine-specific location. Hard-coding it in the test file commits that path.
Pragmatic patterns:

- **Dedicated per-machine test file**, `@Disabled` by default, path captured
  as a `private static final Path DIST = Paths.get("...")`. Uncomment /
  enable only when manually running.
- **Read from a gitignored file** (e.g., `~/.workato-agent-dist`) in a
  `@BeforeAll`, fallback-skip if absent.
- **Explicit path from an environment variable** your test reads directly —
  `Paths.get(System.getenv("MY_AGENT_DIST"))`.

The runner itself never consults `WORKATO_AGENT_HOME` or system properties —
`create(...)` requires an explicit `Path`.

### Full configuration surface

Builder methods on `DeployedAgentLauncher`:

| Method                           | Scope           | Purpose                                                                                                                                                                                                                                                                                                        |
|----------------------------------|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `create(profileName, path)`      | —               | Entry point. If `path/lib/` exists it is used as a deployed agent; otherwise the launcher falls back to the locally-built agent (auto-discovered under `modules/agent-launcher/build/install/*`) for jars while still reading certs + base config from `path/conf/`. Fails if neither is available.            |
| `controllerClass(Class)`         | default profile | Type-safe setter for the `controllerClass` key.                                                                                                                                                                                                                                                                |
| `property(key, value)`           | default profile | Single-key add / override.                                                                                                                                                                                                                                                                                     |
| `properties(Map)`                | default profile | Bulk-merge entries.                                                                                                                                                                                                                                                                                            |
| `yaml(classpathResource)`        | default profile | Load profile body from a classpath YAML fixture (`extensions.{profileName}:…` shape).                                                                                                                                                                                                                          |
| `extensionProperty(name, k, v)`  | foreign profile | Add / override a single key on a profile other than the primary one.                                                                                                                                                                                                                                           |
| `extensionProperties(name, Map)` | foreign profile | Bulk variant of `extensionProperty`.                                                                                                                                                                                                                                                                           |
| `extensionCodeSource(Class...)`  | classpath       | Explicitly add a class's code source URL to the agent's isolated classloader. Deduplicates with the one inferred automatically from `controllerClass`.                                                                                                                                                         |
| `extensionCodeSource(Path...)`   | classpath       | Add classes directories / jar files as code source URLs.                                                                                                                                                                                                                                                       |
| `argument(String...)`            | launcher        | Raw CLI arguments forwarded to `Launcher.start`. Passing any of the following opts out of the matching auto-injection (both space and `=` forms are recognised): `--cert` (trust-store), `--workato-properties` (loose `conf/workato.properties` override), `--key` / `--pem` / `--key-id` (activation files). |
| `systemProperty(k, v)`           | JVM             | Sets a JVM-wide system property before the agent starts.                                                                                                                                                                                                                                                       |
| `openLocalAccess(int)`           | mode            | Open direct local HTTP access to the agent on an explicit port (adds `--connector-listen`). **Mutually exclusive with the Workato gateway connection** — the agent will not appear online in the Workato cloud.                                                                                                |
| `openLocalAccess()`              | mode            | Shortcut for `openLocalAccess(findFreePort())`. Same gateway trade-off applies.                                                                                                                                                                                                                                |
| `baseConfig(Path)`               | config          | Override which `config.yml` the runner merges the generated profile(s) into. Defaults to `{dist}/conf/config.yml` when present.                                                                                                                                                                                |
| `start()`                        | lifecycle       | Launches the agent in-process; returns `RunningAgent`.                                                                                                                                                                                                                                                         |

Accessors on `RunningAgent` (`AutoCloseable`):

| Method                                            | Returns / effect                                                                                                                                                                                                 |
|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `port()`                                          | Bound TCP port opened by `openLocalAccess(...)`. Throws `IllegalStateException` when local access was not opened.                                                                                                |
| `profileName()`                                   | The default profile name.                                                                                                                                                                                        |
| `profile()`                                       | The final merged body of the default profile.                                                                                                                                                                    |
| `extensionProfile(name)`                          | The final merged body of any registered profile.                                                                                                                                                                 |
| `extensionProfiles()`                             | Snapshot of all registered profile bodies.                                                                                                                                                                       |
| `httpGet(Controller::method [, pathVars])`        | Issues GET at the URL derived from the controller method's `@GetMapping` + the profile mount point (connector mode only). Arity overloads cover controller signatures from zero through ten declared parameters. |
| `httpPost(Controller::method, body [, pathVars])` | Issues POST with a JSON-serialised body (connector mode only). Same ten-arity ladder as `httpGet` — enough for `@RequestBody` plus `@PathVariable` / `@RequestHeader` / `@RequestParam` / … combos.              |
| `urlFor(Controller::method [, pathVars])`         | The full URL (`http://127.0.0.1:{port}/ext/{profile}{route}`) a request to the given controller method would target. Same ten-arity ladder as `httpGet`.                                                         |
| `requestFor(Controller::method [, pathVars])`     | An `HttpRequest.Builder` with URL + HTTP method pre-filled from the annotation; caller adds `.header(...)` / `.POST(publisher)` / `.timeout(...)` and finishes with `.build()`. Pairs with `send(request)`.      |
| `send(HttpRequest)`                               | Sends the request through the same `HttpClient` the convenience methods use (5-second connect timeout, string body handler).                                                                                     |
| `awaitGatewayConnected(Duration)`                 | Blocks until `"Connected to gateway"` appears in the agent's log, or times out. Returns the full matching line.                                                                                                  |
| `classLoader()`                                   | The agent's isolated `URLClassLoader`.                                                                                                                                                                           |
| `stop()` / `close()`                              | Shuts the agent down, restores stdout, releases the classloader.                                                                                                                                                 |

Static helpers:

| Method                                                              | Purpose                                                             |
|---------------------------------------------------------------------|---------------------------------------------------------------------|
| `DeployedAgentLauncher.loadExtensionProfile(resource, profileName)` | Low-level: load one profile section from a YAML classpath resource. |
| `DeployedAgentLauncher.findFreePort()`                              | Allocate a free TCP port via `ServerSocket(0)`.                     |

### Isolation between tests

Each `start()` call mutates process-global state inside the agent — a
singleton Spring `Application` context, Netty event loops, scheduled
executors. The agent's `Launcher` state machine does not permit a second
`start()` in the same JVM; a second attempt either fails fast or hangs.

Run `DeployedAgentLauncher`-driven tests **one method at a time**. Three
patterns, in order of preference:

- **Separate Gradle invocations per method**, pointed at `--tests` with the
  specific method name:

  ```shell
  ./gradlew :my-ext:test --tests "com.example.MyExtIntegrationTest.oneCase"
  ./gradlew :my-ext:test --tests "com.example.MyExtIntegrationTest.anotherCase"
  ```

- **One JVM per test method** via Gradle's `forkEvery`:

  ```gradle
  // build.gradle for the extension's test module
  tasks.named('test') {
      // Run each @Test in its own JVM so DeployedAgentLauncher can start the
      // real agent repeatedly across methods.
      forkEvery = 1
  }
  ```

- **Keep the integration-test class `@Disabled` by default**, run individual
  methods manually from the IDE when debugging.

### Debugging

The agent runs in the test JVM, so starting the test from your IDE's debugger
is enough — breakpoints inside `agent-core`, `agent-launcher`, and your own
controller fire normally. The controller class is loaded by the agent's
isolated `URLClassLoader`, so the `Class` instance that fires the breakpoint
is distinct from the one your test code holds, but source-line breakpoints
are unaffected.

## Troubleshooting

| Symptom                                                                                                                                        | Likely cause                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
|------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `IllegalStateException: Harness has not been started — call start() or use @RegisterExtension`                                                 | You called `mockMvc()` / `agentExtensionApi()` before `start()`. Either register the harness with `@RegisterExtension` or call `.start()` in your own `@BeforeAll`.                                                                                                                                                                                                                                                                                                        |
| `IllegalStateException: controller(...) may be called only once per harness`                                                                   | You chained `.controller(X.class)` and `.controller(y)` on the same harness. Pick one; the harness holds exactly one controller per instance.                                                                                                                                                                                                                                                                                                                              |
| `IllegalStateException: A controller must be available — call .controller(...), or load a YAML whose extensions.{p} section …`                 | Neither `.controller(...)` was called nor the YAML section carries a `controller:` / `controllerClass:` key.                                                                                                                                                                                                                                                                                                                                                               |
| `IllegalStateException: Controller class declared in YAML for extension '{p}' is not on the test classpath: {class}`                           | YAML `controller:` names a class that cannot be loaded by the test classloader (typo or missing dependency).                                                                                                                                                                                                                                                                                                                                                               |
| `IllegalStateException: Overloaded method {name} on {Class} — the method-reference form does not disambiguate overloads …`                     | Two methods share the same name on the controller. Use the string form `agent.urlFor("/op")`.                                                                                                                                                                                                                                                                                                                                                                              |
| `IllegalStateException: No HTTP method inferable from annotations on …`                                                                        | The controller method has no `@GetMapping` / `@PostMapping` / … and no `@RequestMapping(method = ...)`. Add one, or use the string form.                                                                                                                                                                                                                                                                                                                                   |
| `@PathVariable id` arrives as `arg0` / substitution does not replace `{id}` in the URL                                                         | Controller compiled without `-parameters`. Declare the name explicitly: `@PathVariable("id") String id`.                                                                                                                                                                                                                                                                                                                                                                   |
| `404` with `MockMvcMatchers.status().isNotFound()` on a correct-looking path                                                                   | You asserted against the raw servlet path (`/op`) instead of the prefixed URL. Use `agent.urlFor("/op")` or `agent.request(Controller::method)`.                                                                                                                                                                                                                                                                                                                           |
| `NoSuchBeanDefinitionException` when the controller autowires a service                                                                        | You did not register the service with `.bean(name, instance)`, `.configuration(cfg.class)`, or `.parentContext(ctx)`. The harness context does not scan — nothing is implicit.                                                                                                                                                                                                                                                                                             |
| **`DeployedAgentLauncher`** — `Agent SSL context is incomplete` on startup                                                                     | The dist's `conf/cert.pem` / `conf/cert.key` are missing or unreadable. Full gateway mode requires an activated distribution; call `openLocalAccess()` if you only need direct local HTTP.                                                                                                                                                                                                                                                                                 |
| **`DeployedAgentLauncher`** — direct local HTTP works but Workato cloud does not see the agent                                                 | By design. `--connector-listen` (set by `openLocalAccess(...)`) triggers `AgentEnabled.isEnabled() == false`, which suppresses the outbound gateway beans. Switch to full gateway mode (omit `openLocalAccess(...)`) and verify with `awaitGatewayConnected(Duration)`.                                                                                                                                                                                                    |
| **`DeployedAgentLauncher`** — `java.util.concurrent.TimeoutException` from `awaitGatewayConnected`                                             | The agent is not completing the mTLS handshake within the budget. Check cert files are present and valid, egress to the gateway host is open, and the dist is actually activated for your workspace.                                                                                                                                                                                                                                                                       |
| **`DeployedAgentLauncher`** — `NoClassDefFoundError: com/workato/agent/extension/api/AgentExtensionApiV1`                                      | The dist is older than the extension API your controller compiles against. Either use a newer dist, point `path` at a loose-conf directory (no `lib/`) so the local-build fallback supplies the current jars, or avoid `AgentExtensionApiV1` in the test controller (configure via `@Value("${…}")` only).                                                                                                                                                                 |
| **`DeployedAgentLauncher`** — `GatewayCheck` fails with `"unable to find valid certification path to requested target"` (local-build fallback) | The local build's `workato.properties / gateway.ca.file` targets a different environment than the external cert does (e.g., prod jars + preview cert). The loose-conf trust override expects every PEM listed in `gateway.ca.file` to actually be present under `path/conf/` — missing files are skipped silently and partial chains fail validation. Verify the layout, or pass `--cert /abs/path.pem` manually via `argument(...)` to override.                          |
| **`DeployedAgentLauncher`** — `Agent version: X` in the log is not the version you expect from the dist's jar name                             | The agent's `Config.init` only reads `workato.properties` from the jar classpath; the one in `conf/` is ignored by default. The launcher auto-injects `--workato-properties <abs path>` when it finds `path/conf/workato.properties`, which makes every entry (`agent.version`, `agent.default.gateway`, `gateway.ca.file`, …) override the jar's. Verify the loose file exists next to `cert.pem`; or pass `--workato-properties /abs/path` manually via `argument(...)`. |
| **`DeployedAgentLauncher`** — second `start()` in the same JVM fails or hangs                                                                  | The agent's `Launcher` state machine does not allow a re-start. Run each `DeployedAgentLauncher`-driven test in its own JVM (one `@Test` method per Gradle invocation).                                                                                                                                                                                                                                                                                                    |
