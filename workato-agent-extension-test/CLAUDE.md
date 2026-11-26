# Extension Test Harness

> **Workspace note.** This module was moved out of the OPA repo
> (`~/Projects/opa/modules/extension-test/`) into the standalone
> `opa-extensions` workspace. Cross-references below to `modules/agent-core/…`
> or `modules/agent-launcher/…` point into the OPA repo (`~/Projects/opa/`),
> which consumes this module as a published artifact (published to GitHub
> Packages — see root `CLAUDE.md`).

Test-time module shipping two complementary tools for agent extensions:

- **`AgentExtensionHarness`** — in-memory `MockMvc`-based harness that mirrors
  the agent's per-endpoint Spring context without pulling the agent in. Fast,
  no I/O, covers the ~95% of extension tests where you just need to pin Spring
  wiring and route matching.
- **`DeployedAgentLauncher`** — in-process launcher for the real agent against
  a deployed distribution on disk. Covers the ~5% that `AgentExtensionHarness`
  cannot: classloader isolation from `lib_ext/`, real Jetty binding, real mTLS
  tunnel to the Workato gateway, end-to-end extension deployment.

> **Companion customer-facing doc: `README.md` in this directory.**
> This CLAUDE.md is the *maintainer* view (invariants, implementation seams).
> README.md is the *consumer* view (how to use both tools in your tests).
>
> **Keep them in sync.** Any public API change on `AgentExtensionHarness` or
> `DeployedAgentLauncher` — new builder method, renamed accessor, new
> behaviour — must be reflected in README.md in the same change.

## Purpose

Customers writing an extension have two unsatisfactory options without this
module:

1. **Full agent boot** — the pattern the agent's own tests use
   (`modules/agent-launcher/src/test/java/com/workato/agent/extension/*IntegrationTest.java`).
   Requires `agent-core`, `agent-launcher`, a real `Application` context, Jetty
   on a free port, and the full agent dep closure. Fine for maintainers; absurd
   for a customer who just wants to test their 300-line extension.
2. **Hand-rolled fakes** — customer implements `AgentExtensionV1` themselves,
   wires up Spring MVC manually, duplicates `EndpointPropertySource` semantics
   by guesswork. Fragile, drifts from production, and silently miscovers edge
   cases (dotted keys, unmodifiable contract, servlet-path mapping).

`AgentExtensionHarness` ships option 3: a tiny harness that mirrors the agent's
per-endpoint Spring context (same bean names, same URL prefix, same
property-source semantics) without pulling in the agent itself.

`DeployedAgentLauncher` reclaims option 1 as a reusable test tool: it still
boots a real agent, but hides the launcher wiring (classloader isolation,
cert-path injection, config merging, log scanning) behind a one-call builder.
Zero compile-time dep on `agent-core` / `agent-launcher` — the agent is loaded
reflectively from the on-disk dist, so the extension-test module stays as
dep-light as before.

## Invariant: dependency closure

Compile-time deps are intentionally minimal:

| Dependency                                                 | Why                                                       |
|------------------------------------------------------------|-----------------------------------------------------------|
| `project(":extension-api")`                                | The sole agent-side dep: types customers compile against  |
| `org.springframework:spring-webmvc`                        | DispatcherServlet / HandlerMapping — the runtime          |
| `org.springframework:spring-test`                          | MockMvc                                                   |
| `jakarta.servlet:jakarta.servlet-api`                      | Spring MVC + MockMvc leak jakarta.servlet.* types         |
| `com.fasterxml.jackson.core:jackson-databind`              | Default JSON converter in Spring MVC                      |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | YAML fixture parsing (`.yaml(resource)`)                  |
| `org.junit.jupiter:junit-jupiter-api`                      | For the BeforeAll/AfterAll/BeforeEach/AfterEach callbacks |
| `org.slf4j:slf4j-api`                                      | Diagnostic logging; implementation provided by customer   |

**Must not** add: `agent-core`, `connector-api`, Jetty, logback, or anything
that would make the harness heavier than the extension it tests.
`DeployedAgentLauncher` loads the agent reflectively from the on-disk dist via
an isolated `URLClassLoader` — the agent's jars never enter extension-test's
compile classpath, which is what keeps the invariant above tight.

## Public API Surface

Two public classes — `AgentExtensionHarness` and `FakeAgentWebhookV1`. Everything else
(`FakeAgentExtension`, `EndpointPropertySource`, `ControllerMethodResolver`) is
package-private.

`FakeAgentWebhookV1` is public because customer tests sometimes want to build it standalone
(unit tests that construct a controller by hand rather than through the harness). The harness
owns one by default and exposes it through `AgentExtensionHarness.webhook()`.

```java
public final class AgentExtensionHarness
        implements BeforeAllCallback, AfterAllCallback,
        BeforeEachCallback, AfterEachCallback {

    // Builder
    public static AgentExtensionHarness extension(String profileName);

    public AgentExtensionHarness controller(Class<?> controllerClass);   // optional if YAML has it

    public AgentExtensionHarness controller(Object controllerInstance);  // optional if YAML has it

    public AgentExtensionHarness property(String key, Object value);

    public AgentExtensionHarness yaml(String classpathResource);

    public AgentExtensionHarness connection(String key, Object value);

    public AgentExtensionHarness connection(Map<String, Object> entries);

    public AgentExtensionHarness connectionYaml(String classpathResource);

    public AgentExtensionHarness bean(String name, Object instance);

    public AgentExtensionHarness configuration(Class<?> configurationClass);

    public AgentExtensionHarness parentContext(ApplicationContext parentContext);

    public AgentExtensionHarness webhook(FakeAgentWebhookV1 webhook);   // optional pre-built fake

    // Lifecycle (manual; JUnit 5 wires these automatically)
    public AgentExtensionHarness start();

    public void stop();

    // Accessors
    public MockMvc mockMvc();

    public AgentExtensionV1 agentExtension();

    public FakeAgentWebhookV1 webhook();   // the fake the controller sees via webhook()

    public ConnectionProperties connectionProperties();   // shortcut for agent.getContext().getConnectionProperties()

    public AnnotationConfigWebApplicationContext webApplicationContext();

    public String profileName();

    public String urlFor(String path);

    // Method-reference overloads — read @GetMapping / @PostMapping / … off the controller
    // and build the full URL (prefix + class-level mapping + method path) without string
    // duplication. See "7. Controller method-reference resolution" below.
    public <C, R> String urlFor(Ref0<C, R> ref);

    public <C, A, R> String urlFor(Ref1<C, A, R> ref, Object... pathVars);

    public <C, R> MockHttpServletRequestBuilder request(Ref0<C, R> ref);

    public <C, A, R> MockHttpServletRequestBuilder request(Ref1<C, A, R> ref, Object... pathVars);

    // Nested SAM types; customers never implement these explicitly.
    // Ref0..Ref10 cover any realistic controller signature. The harness itself only consumes
    // Ref0/Ref1 for urlFor/request; the rest (Ref2..Ref10) exist so DeployedAgentLauncher's
    // httpGet / httpPost / urlFor / requestFor overloads can accept method references to
    // multi-parameter controller signatures (@RequestBody + @PathVariable + @RequestHeader + …).
    @FunctionalInterface
    public interface Ref0<C, R> extends Serializable {
        R apply(C c);
    }

    @FunctionalInterface
    public interface Ref1<C, A, R> extends Serializable {
        R apply(C c, A a);
    }

    @FunctionalInterface
    public interface Ref2<C, A1, A2, R> extends Serializable {
        R apply(C c, A1 a1, A2 a2);
    }

    @FunctionalInterface
    public interface Ref3<C, A1, A2, A3, R> extends Serializable {
        R apply(C c, A1 a1, A2 a2, A3 a3);
    }

    @FunctionalInterface
    public interface Ref4<C, A1, A2, A3, A4, R> extends Serializable {
        R apply(C c, A1 a1, A2 a2, A3 a3, A4 a4);
    }
    // Ref5..Ref10 follow the same pattern — one more type parameter / method parameter each.
}
```

## Load-bearing design decisions

### 1. MockMvc, not an embedded Jetty

`MockMvc` runs Spring MVC without a real HTTP stack. Pros: fast, no port
coordination, no socket cleanup. Cons: does not catch Jetty-specific bugs
(buffering, chunking, timeout boundaries). The latter class of bugs is covered
by the agent's own integration tests inside `agent-launcher`; the harness is for
customer-side logic.

### 2. Servlet-path emulation via `defaultRequest(...).servletPath(...)`

Production agent mounts each extension at servlet mapping `/ext/{name}/*`;
Spring MVC then matches `@RequestMapping` relative to the servlet path. In
MockMvc there is no servlet mapping — Spring would see `/ext/my-profile/op`
as the full handler path and fail to match `@RequestMapping("/op")`.

Fix: pre-set `servletPath = "/ext/{name}"` on every MockMvc request through
`MockMvcBuilders.defaultRequest(get("/").servletPath(...))`. Spring MVC then
computes `pathInContext = requestURI - contextPath - servletPath = "/op"`,
which matches. Requests coming in through `agent.urlFor(...)` therefore behave
production-identically.

This is why `.urlFor()` must always be used — a raw `/op` works too (because
`defaultRequest.servletPath` is a merge default), but it does not catch
absolute-URL bugs the way the prefixed path does.

### 3. `EndpointPropertySource` — direct map lookup, not hierarchical

Copied semantically from
`com.workato.agent.extension.EndpointPropertySource` in `agent-core`. Dots in
keys are literal. A property `mail.pop3.username` is retrieved as that string;
no traversal into nested maps. This is the production contract; replicating
it keeps `@Value` behaviour identical.

Also preserves the production naming convention
`"{profileName}PropertySource"` — one customer pattern locates the
PropertySource by class and strips the suffix. Changing either the class name
or the suffix is a breaking change for them.

The connection payload is deliberately **not** registered as a PropertySource
on the Environment — plain-field `@Value` would otherwise reach it, which
violates the visibility matrix in decision #4b. Connection reachability is
instead layered on through the autowire-candidate resolver and the handler-method
argument resolver. The `EndpointPropertySource.forConnection(...)` factory is
still present and exposed as a utility for future custom wiring, but the
harness itself does not call it at runtime. If a future revision wants
connection content to be environment-visible for some targeted subset
(say, via a custom profile), the factory is the obvious entry point.

### 4. `AgentConfiguration` is unmodifiable in the harness too

`FakeAgentExtension.unmodifiableConfiguration(...)` wraps the properties in
`Collections.unmodifiableMap(new LinkedHashMap<>(src))` and exposes the view
via an `AbstractMap`-based `AgentConfiguration`. Identical to the production
`AgentExtensionV1Impl.UnmodifiableConfiguration`.

Why not just return a `LinkedHashMap`? Because a customer whose controller
accidentally mutates the configuration (`cfg.put(...)`) would see the test
pass and production fail with `UnsupportedOperationException`. Keeping the
test's contract strictly as tight as production's is the point.

### 4b. `ConnectionProperties` fidelity: unmodifiable, SHA-256 hex, profile wins

`FakeAgentExtension.unmodifiableConnection(...)` mirrors the
`unmodifiableConfiguration(...)` helper for the connection view — same
defensive-copy + `Collections.unmodifiableMap` pattern, so the fake plugs the
`Entry.setValue()` attack vector that pure `LinkedHashMap` subclassing would
miss. The production counterpart in `agent-core` is expected to use the same
wrapper.

`contentHash()` is implemented in one shared static — `FakeAgentExtension.sha256Hex(...)` —
which also serves as the oracle for the contract test
(`ConnectionPropertiesTest.ContentHashSemantics.hashMatchesKnownVector`). The
canonical form is documented on the API interface itself; both the test fake
and the production impl must compute the hash the same way, bit-for-bit, so a
cache key computed in a test run equals the one the agent would compute at
runtime for the same payload. If the canonical form ever changes (e.g., value
serialisation beyond `String.valueOf`), update both sides *and* the public
Javadoc in the same change.

#### `@Value` visibility matrix

| `@Value` site                          | Sees profile | Sees connection |
|----------------------------------------|--------------|-----------------|
| Plain-type field (`String`, `int`, …)  | yes          | no              |
| `Supplier<T>` field                    | yes          | yes             |
| Handler-method parameter (plain)       | yes          | yes             |
| Handler-method parameter `Supplier<T>` | yes          | yes             |

Three pieces of machinery enforce this matrix:

1. **Profile `PropertySource` in the `Environment`.** Only the profile source
   lives on the `Environment`. Plain-field `@Value` goes through Spring's
   default placeholder resolution, which walks the `Environment` — so plain
   fields are profile-only by construction. A plain field pointed at a
   connection-only key fails bean creation at the
   `PropertySourcesPlaceholderConfigurer` registered by `MvcConfig` (pinned by
   `PlainFieldValueVisibilityTest.plainFieldReferringToConnectionOnlyKeyFailsBeanCreation`).

2. **`ProfileAndConnectionAutowireCandidateResolver`.** A
   `ContextAnnotationAutowireCandidateResolver` subclass set on the bean
   factory by `HarnessBeanRegistrar.postProcessBeanFactory`. Its
   `getSuggestedValue(DependencyDescriptor)` intercepts `@Value` targets whose
   type is `Supplier<T>` and returns a resolving `Supplier` that reads from
   profile and connection via `PropertyResolution`. Plain-type `@Value` falls
   through to the superclass unchanged. Covers field, constructor, and setter
   injection; pinned by `SupplierValueControllerTest`.

3. **`ProfileAndConnectionValueArgumentResolver` + `ArgumentResolverRegistrar`.**
   The resolver is a `HandlerMethodArgumentResolver` that supports `@Value` on
   handler-method parameters (plain or `Supplier`-shaped). The registrar is a
   `BeanPostProcessor` that, after `RequestMappingHandlerAdapter` finishes
   `afterPropertiesSet`, **prepends** the resolver to the adapter's resolver
   list so it wins over Spring's built-in `ExpressionValueMethodArgumentResolver`.
   `WebMvcConfigurer#addArgumentResolvers` is not usable here — it appends
   customs after defaults. Pinned by `MethodParamValueControllerTest` and the
   method-param side of `SupplierValueControllerTest`.

#### Collision rule

Profile wins on collision at the `PropertyResolution.resolve(...)` call site:
when the same key is present in both maps, the resolver logs an `ERROR`
naming the profile and shadowed key, returns the profile value, and leaves
the connection map intact (callers can still read the raw value verbatim).
Pinned by `ConnectionPropertiesTest.ProfileWinsOnCollision`. There is no
upfront filter — detection happens on each resolution call, so the log fires
at the actual moment of injection.

### 4a. `getTools()` is a fresh `JsonMapper` in the harness

`FakeAgentExtension` implements `AgentExtensionV1.getTools()` by returning an
`AgentToolsV1` whose `objectMapper()` is a freshly-constructed
`tools.jackson.databind.json.JsonMapper.builder().build()` — vanilla defaults,
no custom modules. Production's `AgentExtensionV1Impl` returns the agent's
shared Spring-managed `ObjectMapper`, so there is a fidelity gap: if a customer
test relies on Jackson modules the agent registers (e.g., a `JavaTimeModule`
for `Instant` fields), the harness will silently produce different results.

Why not mirror production and inject the test's own Spring `ObjectMapper` bean
automatically? Because the harness is dependency-light by design — it does not
run a `ComponentScan`, and we deliberately avoid magic bean autodiscovery
(same rationale as the "no `@ComponentScan`" trade-off below). Customers who
need module parity should either register their configured `ObjectMapper` as
a bean via `.bean("objectMapper", mapper)` and have the controller autowire
it directly, or construct one in the test and pass it to
`WebhookResponse.as(type, mapper)` explicitly.

The fake lives in `FakeAgentExtension` as a private `FakeTools` record — one
line of implementation. If tool surface grows beyond `objectMapper()` (future
candidates: scheduler, metrics registry, HTTP client), extend both production
and `FakeTools` in the same PR to keep the fidelity invariant intact.

### 5. Both JUnit 5 lifecycle variants

`AgentExtensionHarness` implements all four of `BeforeAllCallback`,
`AfterAllCallback`, `BeforeEachCallback`, `AfterEachCallback`. Reason:

- `@RegisterExtension` on a `static` field (top-level class) triggers
  class-level callbacks → one Spring context per test class. Faster.
- `@RegisterExtension` on a non-static field — required inside `@Nested`
  classes, where static fields are not allowed — triggers instance-level
  callbacks → one context per test method. Slower, but necessary.

Both `start()` and `stop()` are idempotent so overlapping invocations are
harmless.

### 6. YAML fixtures mirror the agent's `config.yml` layout

`.yaml(classpathResource)` reads a resource whose structure matches the agent's
production config:

```yaml
extensions:
  { profile-name }:
    controller: com.example.X     # ignored by the harness, present for parity
    other-key: value
```

The harness extracts the `extensions.{profileName}` section and feeds its
children into the property map — same shape, same keys, same dot semantics
production sees. Customers write one YAML, use it in both production and
tests. Inline `.property(k, v)` chained after `.yaml()` wins over YAML — the
`merged.putAll(properties)` pass in `yaml()` ensures that.

The `controller` / `controllerClass` key is passed through to the
configuration view verbatim (so `getProfileConfiguration()` matches
production), but it does **not** auto-register the controller class. Keeping
registration explicit via `.controller(...)` avoids reflection-magic failures
on class-not-found and keeps the test wiring visible.

Negative paths are pinned in `BuilderValidationTest`: missing resource →
`IllegalArgumentException`; missing `extensions:` section →
`IllegalStateException`; missing target profile → `IllegalStateException`
mentioning the profile name.

### 7. Controller method-reference resolution

`urlFor(ref)` / `request(ref)` take a `Serializable` SAM method reference,
decode the generated `writeReplace()` → `SerializedLambda`, resolve the
implementation class and method name, read Spring web annotations
(`@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`,
`@PatchMapping`, `@RequestMapping`) off the method, combine the class-level
`@RequestMapping` path with the method-level route, and substitute every
`{name}` placeholder with the next positional entry from `pathVars` whose
parameter carries `@PathVariable`.

The resolver lives in `ControllerMethodResolver` (package-private). Two
overloads cover arities 0 and 1 (`Ref0`, `Ref1`); methods with more than one
total parameter (common when a `@RequestBody` sits alongside a
`@PathVariable`) still work through `Ref1` because the resolver only counts
`@PathVariable` parameters when substituting — non-path parameters are
skipped silently.

Rejected trade-offs:

- **No proxy-based approach** (Spring HATEOAS style, `methodOn(Controller)`).
  Would require ByteBuddy or cglib in the compile classpath — unacceptable
  dependency weight for a tiny harness.
- **No higher-arity overloads on the harness itself** (`urlFor(Ref2, …)` /
  `request(Ref3, …)`). The 0 and 1 cases land ~95% of real controllers;
  everything else falls back cleanly to the string form
  `agent.urlFor("/path")`. The `Ref2` / `Ref3` SAM types *do* exist as nested
  interfaces on this class — `DeployedAgentLauncher.RunningAgent.httpPost` /
  `requestFor` consume them to accept method references to controllers with
  `@RequestBody` + `@PathVariable` / `@RequestHeader` combos. Add the matching
  harness-side overloads only if a real customer scenario demands them.
- **Overloaded controller methods are rejected explicitly**
  (`IllegalStateException "Overloaded method ..."`). Resolving overloads from
  a `SerializedLambda` requires parsing the implementation signature; keeping
  the resolver simple is worth the trade — overloads are rare on
  `@RestController` methods and the string form is always available.
- **URL path variables are URL-encoded** via `URLEncoder.UTF_8`. Values with
  slashes or question marks will be percent-encoded, matching production
  Spring routing. If a test explicitly wants a raw substring, it should use
  the string form.

### 8. Parent-context inheritance via `.parentContext(ApplicationContext)`

Mirrors production exactly: `Server.createRestDispatcher` in `agent-core`
calls `wac.setParent(applicationContext)` so each extension's isolated child
context sees the agent's main context's beans. The harness exposes the same
mechanism — `.parentContext(ctx)` sets the given context as the parent of
the harness's `AnnotationConfigWebApplicationContext` before `refresh()`.

Primary use case: the customer's test already has an `ApplicationContext`
(`@SpringBootTest`, `@ContextConfiguration`, or a hand-built one). Beans in
that context — stubs, `@Service` layers, `@ControllerAdvice` instances —
become autowire-visible to the extension controller without having to
re-register each one via `.bean(...)` / `.configuration(...)`.

Child-shadows-parent is the standard Spring semantics: if the child context
registers a bean with the same name as a parent bean, lookups by that name
hit the child. This matters for `"agentExtension"` — the harness always
registers its own and must not inadvertently resolve a parent bean of the
same name.

Rejected trade-offs:

- **No auto-discovery of the surrounding `SpringExtension` context.** Would
  require reaching into JUnit 5 `ExtensionContext.getStore(...)` with the
  Spring Test namespace key — brittle (Spring Test internal) and opaque
  (test author cannot see which context the harness picked). Explicit
  `.parentContext(ctx)` keeps the dependency obvious.
- **No component scan option.** Letting the harness run a
  `@ComponentScan("com.example.myext")` would duplicate what the parent
  context mechanism already covers more cleanly, and risks picking up beans
  the test does not want.
- **Parent context lifecycle is the caller's problem.** The harness does not
  start / stop / refresh the parent. If the caller builds a transient parent
  (as in `ParentContextTest`), they close it in their own `@AfterEach`.

### 9. Controller class fallback from YAML `controller` / `controllerClass`

Production the agent's `ExtensionConfigParser` reads the `controller:` /
`controllerClass:` key from each extension's YAML section and loads the class
through the extension classloader. The harness honours the same key: if the
test did not call `.controller(...)` explicitly, `start()` reads the YAML
property, loads the class via `Class.forName(name, true, contextClassLoader)`,
and registers it as the harness's controller. This removes the duplication of
declaring the controller class in both YAML and in `.controller(X.class)` —
one source of truth, same source production uses.

Resolution order inside `start()`:

1. `controllerInstance` set via `.controller(Object)` — wins over everything.
2. `controllerClass` set via `.controller(Class)` — wins over YAML.
3. YAML key `controller`, falling back to `controllerClass`, loaded via
   the thread context classloader.
4. Otherwise `IllegalStateException` with a message naming the profile and
   pointing at both the builder method and the YAML key.

Error paths pinned by `BuilderValidationTest`:

- YAML names a class not on the test classpath → `IllegalStateException`
  mentioning the class name (positive assertion in
  `yamlControllerClassNotFoundOnClasspathIsRejected`).
- YAML names a non-string / empty value → `IllegalStateException` with the
  bad value echoed back.
- Happy path (`yamlControllerClassIsPickedUpWithoutExplicitControllerCall`)
  verifies both that `start()` succeeds and that the registered
  `"controller"` bean is the YAML-declared class.

When explicit `.controller(...)` is still useful:

- Controllers without a no-arg constructor (`.controller(preBuiltInstance)`).
- Overriding YAML with a test-only subclass (e.g., a controller stubbed to
  short-circuit a side effect).
- Tests that run without any YAML at all (`.controller(X.class)` without
  `.yaml(...)` still works — properties can come from `.property(k, v)`).

### 10. Controller registration: both `Class` and `Object`

`controller(Class)` goes through
`AnnotatedGenericBeanDefinition` — same shape the agent's own
`EndpointBeanRegistrar` uses — so Spring handles `@Autowired`, `@Value`,
constructor injection the same way as production.

`controller(Object)` registers a pre-built singleton under the same
`"controller"` bean name but skips autowiring. For customers using Mockito
`@InjectMocks` or any construction pattern that resolves dependencies before
the harness is involved.

Mutually exclusive: calling both throws `IllegalStateException`. One
controller per harness instance.

## `FakeAgentWebhookV1` — scripted fake for the webhook API

Implements `AgentWebhookV1` with:

- **Call log** — every HTTP attempt lands in `allCalls()` / `callsFor(url)` / `callsOfKind(kind)`
  / `lastCall()`, so tests assert on observed behaviour instead of mocking method calls.
- **Scripted responses** — `whenCalled(url)`, `whenCalledMatching(predicate)`, `whenSent(url)`,
  `whenWokenUp(url)` return a `StubBuilder` with `thenReturn(status, body)`,
  `thenReturn(WebhookResponse)`, `thenFail(IOException)`, `thenFailAll()`,
  `thenReturnSequence(...)`.
- **Full RetryPolicy fidelity** — the fake honours `maxAttempts`, `retryableStatusCode`,
  `retryableException`, and throws `WebhookDeliveryException` on exhaustion. **The only
  deviation from production**: all delays are zero (tests must not sleep).
- **Last-wins stub resolution** — multiple matching stubs resolve to the most recently
  registered one. Pattern: a shared `@BeforeEach` registers a broad default; individual
  `@Test`s override with narrower stubs.

The harness holds exactly one fake per test instance, exposed as `harness.webhook()`. Tests
clean state with `harness.webhook().reset()` in a `@BeforeEach` when the harness is
class-level (static field).

### Fidelity invariant

Any change to `AgentWebhookV1Impl` (production, agent-core) that observably alters behaviour
— new retry trigger, different exhaustion semantics, response truncation off-by-one, headers
contract — must be mirrored in `FakeAgentWebhookV1`. The invariant is kept executable by
`WebhookApiProductionParityTest` in `modules/agent-launcher/src/test/java/.../extension/`:
one parametrised test that runs each scenario against both impls and asserts identical
observables. A silent drift between the two is a CI failure, not a subtle production bug.

## `DeployedAgentLauncher` — in-process real-agent launcher

Launches the real agent in the test JVM against a deployed distribution on
disk. One extension profile per launcher instance; the agent runs under an
isolated `URLClassLoader` whose parent is `ClassLoader.getPlatformClassLoader()`
so the surrounding test-runner classpath (Spring, Jackson, etc.) cannot bleed
into the agent's own copies.

### Public API Surface

```java
public final class DeployedAgentLauncher {

    // Single factory — covers both deployed-dist and local-build-fallback cases.
    public static DeployedAgentLauncher create(String profileName, Path path);

    // Default-profile builder methods (target the profile passed to create).
    public DeployedAgentLauncher controllerClass(Class<?> controllerClass);

    public DeployedAgentLauncher property(String key, Object value);

    public DeployedAgentLauncher properties(Map<String, Object> entries);

    public DeployedAgentLauncher yaml(String classpathResource);

    // Foreign-profile builder methods (tweak profiles already in base config.yml).
    public DeployedAgentLauncher extensionProperty(String profileName, String key, Object value);

    public DeployedAgentLauncher extensionProperties(String profileName, Map<String, Object> overrides);

    // Classpath / launcher plumbing.
    public DeployedAgentLauncher extensionCodeSource(Class<?>... classes);

    public DeployedAgentLauncher extensionCodeSource(Path... paths);

    public DeployedAgentLauncher argument(String... args);

    public DeployedAgentLauncher systemProperty(String key, String value);

    public DeployedAgentLauncher baseConfig(Path path);

    // Run-mode opt-in (mutually exclusive with the Workato gateway connection).
    public DeployedAgentLauncher openLocalAccess(int port);

    public DeployedAgentLauncher openLocalAccess();

    // Lifecycle — returns an AutoCloseable handle.
    public RunningAgent start();

    public static Map<String, Object> loadExtensionProfile(String resource, String profileName);

    public static int findFreePort();

    public static final class RunningAgent implements AutoCloseable {
        public int port();                                    // throws if local access was not opened

        public String profileName();

        public Map<String, Object> profile();

        public Map<String, Object> extensionProfile(String name);

        public Map<String, Map<String, Object>> extensionProfiles();

        public ClassLoader classLoader();

        // Convenience: issue GET / POST (JSON body) via the resolved controller route. Each has
        // Ref0..Ref10 overloads so the method-reference literal binds regardless of controller
        // arity (receiver + up to ten declared parameters). Only the Ref0 and Ref1 signatures
        // are shown here — the rest follow mechanically with one more type parameter each.
        public <C, R> HttpResponse<String> httpGet(Ref0<C, R> methodRef);

        public <C, A, R> HttpResponse<String> httpGet(Ref1<C, A, R> methodRef, Object... pathVars);
        // Ref2..Ref10 overloads follow — same shape, body-less, Object... pathVars.

        public <C, R> HttpResponse<String> httpPost(Ref0<C, R> methodRef, Object body);

        public <C, A, R> HttpResponse<String> httpPost(Ref1<C, A, R> methodRef, Object body, Object... pathVars);
        // Ref2..Ref10 overloads follow — same shape, body first, Object... pathVars last.

        // Escape hatch for custom headers, non-JSON bodies, overridden timeouts, mTLS, Expect:
        // 100-continue, etc. Pre-fills URL + HTTP method from the controller annotation; the
        // caller finishes the builder (headers, body publisher) and ships it through send(...).
        // Same Ref0..Ref10 arity ladder as httpGet / httpPost.
        public <C, R> String urlFor(Ref0<C, R> methodRef);

        public <C, A, R> String urlFor(Ref1<C, A, R> methodRef, Object... pathVars);
        // Ref2..Ref10 overloads follow.

        public <C, R> HttpRequest.Builder requestFor(Ref0<C, R> methodRef);

        public <C, A, R> HttpRequest.Builder requestFor(Ref1<C, A, R> methodRef, Object... pathVars);
        // Ref2..Ref10 overloads follow.

        public HttpResponse<String> send(HttpRequest request);

        public String awaitGatewayConnected(Duration timeout) throws TimeoutException, InterruptedException;

        public void stop();                                   // AutoCloseable
    }
}
```

### Load-bearing design decisions

#### 1. In-process launch, not fork+exec

The agent runs in the current JVM via `Class.forName(com.workato.agent.Main)`

+ reflective `Launcher.start(args)`. Pros:

- IDE breakpoints fire inside both the extension and the agent (same debugger
  attach, same source lines).
- No port / PID / SIGTERM / stdout-pipe coordination.
- Clean teardown via reflective `Launcher.shutdown()`.

Cost: the agent's `Launcher` keeps global static state (singleton
`Application` context, netty event loops, scheduled executors). A second
`start()` in the same JVM fails or hangs. **One `@Test` method per JVM** —
either run `--tests "*.oneName"` individually or configure Gradle to fork a
JVM per test.

#### 2. Isolated `URLClassLoader` with the platform parent

```java
new URLClassLoader(composite, ClassLoader.getPlatformClassLoader());
```

- **Why not the system classloader as parent?** The extension-test module (and
  the test runner that uses it) already drags in Spring, Jackson, slf4j,
  JUnit. If any of those are also under the agent's `lib/`, parent-first
  delegation gives Spring/Jackson classes from the surrounding test classpath
  back to the agent → `ClassCastException` across loaders. Platform parent
  guarantees only JDK classes are shared, every other class comes from the
  agent's own jars.
- **Why add the extension's code source into the same loader?** So the
  controller `Class<?>` the agent sees and the one the test sees are literally
  the same `Class<?>` instance — no cross-loader `ClassCastException` when the
  test references `MyController.class`.

#### 3. Deployed-vs-local-build auto-fallback in `create(profileName, path)`

Single factory, resolution order:

1. `path/lib/` exists → treat `path` as a deployed agent. Jars from
   `path/lib/` + `path/lib_ext/`, certs + base `config.yml` from `path/conf/`.
2. `path/lib/` absent → fall back to the locally-built agent at
   `modules/agent-launcher/build/install/*` (auto-discovered via
   `settings.gradle` walk) for jars; certs + base `config.yml` still come from
   `path/conf/`.
3. Neither available → `IllegalStateException` naming both failure modes.

Replaces the previous two-factory API (`create` + `createWithLocalDist`). The
old "local libs against a full external dist's bundled trust-store" scenario
is no longer supported — the `path/lib/` check is a clean signal to pick
layout. If someone really needs the old split-dist behaviour they can delete
`path/lib/` or point `path` at a conf-only directory.

#### 4. Absolute cert-path injection via `--key` / `--pem` / `--key-id` / `--workato-properties`

The agent's `AgentSSLContext.load` looks for `Paths.get("conf/cert.*")` —
relative to CWD. Production `run.sh` works around this by
`cd $WORKATO_AGENT_HOME` before `exec java …`; the test JVM running
`DeployedAgentLauncher` has no such benefit. Without explicit absolute paths
the agent silently starts without client certs and never connects to the
gateway.

`injectAbsoluteCertPaths(confPath, effectiveArgs)` reads each file name from
`path/conf/`, and if present, appends the matching flag. Explicit flags passed
through `argument(...)` take precedence (both the space form `--key file` and
the equals form `--key=file` are recognised).

#### 5. Loose `workato.properties` override

By default `Config.init` reads `workato.properties` once from the jar
classpath (gateway target, trust-CA names, agent version, …). A loose
`path/conf/workato.properties` is **not** picked up automatically.

`injectAbsoluteCertPaths` also injects `--workato-properties <abs path>` when
the loose file is present → `Launcher.start` passes that to
`Config.overrideFromFile`, which overrides every key from the loose file over
the jar-bundled one. This is what lets a self-consistent dist run against a
different gateway environment without rebuilding its jars.

#### 6. Loose trust-store override — `--cert /abs/f1|/abs/f2`

Only strategy left in `injectExternalTrustCerts` after the merge in #3: read
`gateway.ca.file` from the loose `path/conf/workato.properties`, resolve each
PEM as a sibling of `cert.pem`, append `--cert`.

Used by two user-visible scenarios: (a) local-build fallback where the local
jars' bundled trust bundle may not match the gateway the external cert is
bound to, (b) a self-consistent dist redirected at a different gateway. If no
loose `workato.properties` → this is a no-op and the agent's own classloader
resolves the trust store from its jars (the correct behaviour for a
self-consistent dist with no overrides).

Dropped: the "jar extraction" strategy that extracted PEMs from the external
dist's `lib/*.jar`. It only mattered for the deprecated split-dist mode
(#3) — merging factories dropped its only consumer.

#### 7. System.out tee + stdout log scanning

`awaitGatewayConnected(Duration)` scans the captured stdout buffer for
`"Connected to gateway"` — the INFO line production `run.sh` also prints when
the mTLS WebSocket handshake succeeds. No programmatic signal exists on the
agent side.

The tee is installed BEFORE `Class.forName("com.workato.agent.Main")` runs
the agent's logback init, so the ConsoleAppender logback constructs captures
our teed stream as its target. `stop()` restores the original `System.out`.
Any other test code writing to `System.out` while the agent is running is
also tee'd — harmless, but worth knowing.

#### 8. Mutually exclusive run modes

- **Full gateway mode** (default): the agent connects outbound mTLS to the
  Workato gateway and appears online in the cloud UI. Local HTTP is NOT
  exposed; `RunningAgent.port()` throws.
- **Direct local HTTP mode**: `openLocalAccess(...)` injects
  `--connector-listen http://127.0.0.1:{port}`, which flips
  `AgentEnabled.isEnabled()` to false → every `@Conditional(AgentEnabled.class)`
  outbound-gateway bean is suppressed. The agent exposes `/ext/{profile}/*`
  locally but will not connect to the Workato cloud.

The mutual exclusivity is a property of the production agent's bean wiring —
not a test-harness limitation. `RunningAgent.port()` throws
`IllegalStateException` when local access was not opened;
`awaitGatewayConnected()` times out when it was.

#### 9. HTTP convenience helpers that read `@GetMapping` / `@PostMapping`

`RunningAgent.httpGet(MyController::op)` / `httpPost(MyController::op, body)`
reuse the same `ControllerMethodResolver` the `AgentExtensionHarness` uses:
decode the `SerializedLambda`, read the Spring web annotation off the method,
build `http://127.0.0.1:{port}/ext/{profile}{methodPath}`. Removes string
duplication of the controller's route.

All require connector mode (via `requireLocalAccessOpened()`); the resolver
is called with the **surrounding** TCCL (not the agent's) so Spring's
annotation classes compared via `instanceof` are the ones the resolver was
compiled against. See the comment in `ControllerMethodResolver.loadClass`.

**Arity overloads.** `httpGet` / `httpPost` / `urlFor` / `requestFor` each
have `Ref0`..`Ref10` overloads so a controller method with any realistic
parameter mix (`@RequestBody` + `@PathVariable` + `@RequestHeader` +
`@RequestParam` + …) still binds to the method-reference literal
`MyExtension::handle`. The resolver never invokes the SAM — only the
serialized lambda metadata matters — but Java insists on a functional-interface
target with matching arity for the method-reference to compile. `pathVars`
carries one value per declared `@PathVariable` only; non-path parameters are
skipped by the resolver. Controllers with more than ten declared parameters
are rare enough that the fallback is to build the request from the string URL
form through `send(HttpRequest)`.

**Builder escape hatch.** `requestFor(MyController::op)` returns an
`HttpRequest.Builder` with URL + HTTP method pre-filled from the annotation,
plus the convenience defaults (`Accept: application/json`, 10-second
`timeout`). Callers decorate it with `.header(...)` / `.POST(publisher)` /
`.timeout(...)` and finish with `.build()` + `agent.send(request)`. One
builder closes custom headers, non-JSON bodies, overridden timeouts, mTLS,
`Expect: 100-continue`, etc. without multiplying overloads. `urlFor` returns
the same resolved URL as a string, for tests that would rather build the
request through a bare `HttpClient`.

#### 10. Startup INFO summary

`logStartupSummary(effectiveArgs, port)` emits a single multi-line INFO line
just before `Launcher.start`, listing: profile + controller, jars source
(deployed vs local-build fallback), conf source, base `config.yml` path,
loose `workato.properties` override (or jar-bundled default), gateway
trust-store origin (loose / jar-bundled / explicit --cert), run mode,
foreign profiles, user-supplied system properties and launcher args.

Lets the test author verify at a glance the agent is wired as expected,
without parsing the launcher CLI by hand or diffing against production
`run.sh`.

### `DeployedAgentLauncher` trade-offs

- **Not CI-runnable.** Every test needs a deployed+activated agent dist on
  disk (real cert.pem/cert.key for a real workspace, matching gateway
  environment). That's per-developer-machine state, not a reproducible CI
  artifact. All `DeployedAgentLauncher`-driven integration tests must be
  `@Disabled` by default and run manually — either for end-to-end
  verification or to reproduce a mysterious issue that only shows up inside
  a full agent process. Any scenario that boils down to Spring MVC wiring
  (routing, `@Value`, autowired beans) belongs in an `AgentExtensionHarness`
  test instead, which runs fine on CI.
- **One test per JVM** (see decision #1). Documented in README's "Isolation
  between tests" section; integration tests in this repo are run manually,
  one `@Test` at a time.
- **JVM-wide `systemProperty(k, v)` mutations** are observable to anything
  else running in the same JVM. The launcher does not restore the previous
  value on `stop()` — intentional, since the agent itself may read system
  properties long after `start()` returned.
- **`System.out` tee** mutates process-global state too. Restored on
  `stop()`, but concurrent tests writing to stdout would see interleaved
  output.
- **No cross-platform CWD normalization.** The launcher does not `cd` into
  the dist (unlike `run.sh`); it relies on absolute paths for everything it
  injects. Any code inside the agent that still reads `conf/foo` relative to
  CWD will fail — treat that as a bug in the agent to be fixed there, not a
  limitation to patch around here.

## Layout

```
modules/extension-test/
    build.gradle                                             (minimal deps, see table above)
    CLAUDE.md                                                (this file)
    README.md                                                (customer-facing)
    src/main/java/
        com/workato/agent/extension/test/
            AgentExtensionHarness.java                       (public API — in-memory MockMvc harness)
            DeployedAgentLauncher.java                       (public API — in-process real-agent launcher)
            FakeAgentWebhookV1.java                       (public API — scripted fake for the webhook API)
            FakeAgentExtension.java                       (impl of AgentExtensionV1 for the harness)
            EndpointPropertySource.java                      (impl of @Value lookup — profile source only; connection factory kept for future custom wiring)
            PropertyResolution.java                          (shared profile+connection resolver — profile wins, ERROR log on collision)
            ProfileAndConnectionAutowireCandidateResolver.java (@Value Supplier<T> on autowired targets reaches both sources)
            ProfileAndConnectionValueArgumentResolver.java   (@Value on handler method params reaches both sources)
            ArgumentResolverRegistrar.java                   (prepends ProfileAndConnection resolver ahead of Spring defaults)
            ControllerMethodResolver.java                    (SerializedLambda → path + HTTP method; shared)
    src/test/java/
        com/workato/agent/extension/test/
            AbstractExtensionTest.java                       (perform/body/jsonMap helpers for harness tests)
            {Controller}Test.java                            (one top-level harness test per scenario)
            BuilderValidationTest.java                       (lifecycle + YAML-loader negative paths)
            DeployedAgentLauncherIntegrationTest.java        (end-to-end tests; @Disabled by default)
            testext/
                {Controller}.java                            (one top-level controller per scenario)
                PortableEchoController.java                  (OPA 33 — no AgentExtensionV1 dependency)
                EchoController.java                          (OPA 34 — autowires AgentExtensionV1)
                Translator.java, TranslatorConfig.java       (shared collaborators)
    src/test/resources/extensions/
        {profile}.yml                                        (one YAML fixture per scenario)
```

## Trade-offs

**No `@ComponentScan`.** Each test registers exactly what it needs via
`.controller(...)`, `.bean(...)`, `.configuration(...)`. Prevents accidental
leakage of unrelated beans; forces explicit test setup. Downside: a customer
cannot just drop `@ComponentScan("com.example.myext")` at the top of their
test — they list the beans they actually use.

**No `@ControllerAdvice` auto-registration.** Same reason. If a customer
wants `@ControllerAdvice` applied, they declare it through
`.configuration(...)` or register the instance via `.bean(...)`.

**MockServletContext, not real Jetty.** `@EnableWebMvc` asserts a non-null
`ServletContext` at refresh time; `MockServletContext` satisfies that without
starting a server. Interactions that depend on real Jetty behaviour (keep-alive,
`AsyncContext`, chunked transfer-encoding boundaries) are not exercised and
should not be — they are the agent's responsibility, not the extension's.

**Hamcrest / json-path NOT included.** `MockMvc`'s matcher-style assertions
(`content().string(...)`, `jsonPath(...)`) require Hamcrest and (optionally)
json-path, neither in the agent's version catalog. The harness therefore does not
expose those at compile time. Customers who prefer matcher-style assertions
can add Hamcrest / json-path to their own test dependencies; the harness's
own test suite uses AssertJ on `MvcResult` instead to demonstrate the
dependency-light pattern.

**Spring context per test (instance-level) vs. per class (class-level).**
Instance-level is forced for `@Nested` tests. For top-level classes with a
`static` field, class-level is used — much faster. Customers who want per-test
isolation on top-level classes can simply use a non-static field.
