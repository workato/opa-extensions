# Agent Extensions Test Harness — Customer Guide

> If you are reading this to **test an extension** for Workato On-Premise Agent
> (the agent), you are in the right place. For the production API your extension
> compiles against, see `extension-api/README.md`. For the agent maintainers, see
> `CLAUDE.md` in this directory.

## What is it?

A test-time harness that lets you develop and **fully integration-test an the agent
extension without the agent installed**. It stands up a miniature the agent-shaped runtime
for your controller (Spring MVC + Spring's `MockMvc`) with the same bean
contract and the same `/ext/{profile-name}/*` URL layout you get in
production, driven by an in-memory configuration you declare in the test.

Covered without starting the agent:

- `@Autowired AgentExtensionApiV1` into your controller
- `@Value("${key}")` injection with the same direct-lookup semantics as the agent
  (dots in keys are literal, not nesting separators)
- Request routing to `/ext/{profile-name}/*`
- `@PathVariable`, `@RequestBody`, Spring MVC content negotiation
- Service beans and `@ControllerAdvice` your controller depends on
- Unmodifiable-view contract of `AgentExtensionContextV1.getProfileConfiguration()`

Explicitly out of scope: Jetty-specific HTTP behaviour (buffering, chunking,
timeouts), the agent process lifecycle, and classloader isolation. The
production integration tests inside the agent cover those.

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
startup. The `controller` key is also honoured — if you do not call
`.controller(...)` explicitly, the harness loads the class named in YAML and
registers it, matching production's `ExtensionConfigParser` behaviour.
Call `.controller(X.class)` only when you want to override the YAML (e.g., a
test-only subclass) or when the controller needs a non-default constructor —
see "Controllers that depend on other beans" below.

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

If you have overloaded controller methods, or need an HTTP method the
annotations don't disambiguate, fall back to the explicit form —
`get(agent.urlFor("/op"))` — which still works unchanged.

The `@RegisterExtension static` field integrates with JUnit 5 — the harness
starts before the test class and stops after. If you use `@Nested` classes
(where static fields are not allowed), use a non-static field instead; the
harness detects that and starts/stops per test.

**Inline properties.** Prefer YAML — one source of truth for production and
tests. If you need to override a single value for a specific test or set
something not worth putting in the fixture, chain `.property(k, v)` after
`.yaml(...)`; inline properties take precedence over YAML.

### 3. Controllers that depend on other beans

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

This mirrors production exactly — the agent's `Server.createRestDispatcher` calls
`wac.setParent(applicationContext)` so that the main agent context's beans are
reachable from each extension's isolated child context.

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

Internally the harness sets the servlet path on every MockMvc request so
Spring MVC sees the handler-path portion as `/op` while the full request URI
remains `/ext/my-profile/op` — the exact shape production sees. This catches
bugs around absolute URLs the controller might emit (e.g., `Location` headers
that forget the `/ext/{name}` prefix).

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

| Method                    | What it returns                                                             |
|---------------------------|-----------------------------------------------------------------------------|
| `mockMvc()`               | Spring's `MockMvc` for issuing requests                                     |
| `agentExtensionApi()`     | The `AgentExtensionApiV1` the controller sees — handy for direct assertions |
| `webApplicationContext()` | The underlying `AnnotationConfigWebApplicationContext` for advanced wiring  |
| `profileName()`           | The configured profile name                                                 |
| `urlFor(path)`            | The full request path including the `/ext/{profile}` prefix                 |

## Troubleshooting

| Symptom                                                                      | Likely cause                                                                                                                                                                  |
|------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `IllegalStateException: Harness has not been started`                        | You called `mockMvc()` / `agentExtensionApi()` before `start()`. Either register the harness with `@RegisterExtension` or call `.start()` in your own `@BeforeAll`.           |
| `IllegalStateException: controller(...) may be called only once`             | You chained `.controller(X.class)` and `.controller(y)` on the same harness. Pick one; the harness holds exactly one controller per instance.                                 |
| `404` with `MockMvcMatchers.status().isNotFound()` on a correct-looking path | You asserted against the raw servlet path (`/op`) instead of the prefixed URL. Use `agent.urlFor("/op")`.                                                                     |
| `No ServletContext set` at refresh time (hacking on the harness itself)      | You removed or forgot the `MockServletContext`. `@EnableWebMvc` requires one.                                                                                                 |
| `NoSuchBeanDefinitionException` when the controller autowires a service      | You did not register the service with `.bean(name, instance)` or `.configuration(cfg.class)`. The harness context does not scan — every bean has to be registered explicitly. |

## Relation to the production integration tests

The harness **does not** replace the agent's own integration tests of its extension
loader (`ExtensionLoadingIntegrationTest`, `ExtensionRoutingIntegrationTest`,
etc. in `agent-launcher`). Those start the full `Application` boot with real
Jetty and real classloader isolation; they guard the loader itself. This
harness is for **customer** code — a thin, repeatable, no-agent-required
runtime for the extension's own tests. The two serve different consumers.
