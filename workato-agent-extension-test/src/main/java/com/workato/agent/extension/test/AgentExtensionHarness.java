/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentExtensionV1;
import org.junit.jupiter.api.extension.*;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full integration-test harness for a single agent extension — spins up a Spring MVC runtime
 * that mimics the agent's per-endpoint context (same bean names, same {@code /ext/{profile-name}/*}
 * URL prefix, same {@code @Value("${key}")} resolution semantics), without pulling in the agent or
 * running Jetty. The customer exercises the controller through Spring's {@link MockMvc}.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>An {@link AnnotationConfigWebApplicationContext} is created per harness instance. Into it
 *       the harness registers (a) the customer's {@code @RestController}, (b) the
 *       {@code "agentExtension"} singleton (the V1 API fake), and (c) any user-supplied beans
 *       / configuration classes.</li>
 *   <li>An {@link EndpointPropertySource} is added as the first PropertySource in the
 *       {@code Environment}, so {@code @Value("${key}")} on the controller resolves against the
 *       YAML-shaped properties handed to the builder — direct map lookup, dots literal, matching
 *       the production {@code EndpointPropertySource} 1-to-1.</li>
 *   <li>The resulting {@link MockMvc} is mounted at {@code /ext/{profile-name}} so requests go to
 *       the same URL customers will see in production. The agent strips the servlet-prefix via its
 *       dispatcher's servlet mapping; here we achieve the same shape via
 *       {@code addPlaceholderValue + request builders} on {@code MockMvc}.</li>
 * </ul>
 *
 * <h2>Usage — JUnit 5 {@code @RegisterExtension}</h2>
 * <pre>{@code
 * class MyControllerTest {
 *     @RegisterExtension
 *     static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
 *             .controller(MyController.class)
 *             .property("api.url", "https://x")
 *             .property("timeout-ms", 5000);
 *
 *     @Test
 *     void callsEndpoint() throws Exception {
 *         agent.mockMvc()
 *            .perform(get(agent.urlFor("/op")))
 *            .andExpect(status().isOk());
 *     }
 * }
 * }</pre>
 *
 * <h2>Usage — manual lifecycle (TestNG, JUnit 4, @BeforeAll/@AfterAll)</h2>
 * <pre>{@code
 * static AgentExtensionHarness agent;
 *
 * @BeforeAll
 * static void setup() {
 *     agent = AgentExtensionHarness.extension("my-profile")
 *             .controller(MyController.class)
 *             .start();
 * }
 *
 * @AfterAll
 * static void tearDown() { agent.stop(); }
 * }</pre>
 */
public final class AgentExtensionHarness
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private static final ObjectMapper YAML = YAMLMapper.builder().build();

    private final String profileName;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Map<String, Object> connectionProperties = new LinkedHashMap<>();
    private final Map<String, Object> extraBeans = new LinkedHashMap<>();
    private final List<Class<?>> extraConfigurations = new ArrayList<>();

    private Class<?> controllerClass;
    private Object controllerInstance;
    private ApplicationContext parentContext;
    private FakeAgentWebhookV1 webhook;
    private boolean externalWebhook;

    private AnnotationConfigWebApplicationContext webContext;
    private MockMvc mockMvc;

    private AgentExtensionHarness(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Extension profile name must not be blank");
        }
        this.profileName = profileName;
    }

    /**
     * Start a builder for an extension with the given profile name (the YAML key in production).
     */
    public static AgentExtensionHarness extension(String profileName) {
        return new AgentExtensionHarness(profileName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractExtensionSection(Map<String, Object> root, String profile, String path) {
        Object extensions = root == null ? null : root.get("extensions");
        if (!(extensions instanceof Map<?, ?> extMap)) {
            throw new IllegalStateException("YAML resource " + path + " has no 'extensions:' section");
        }
        Object section = extMap.get(profile);
        if (!(section instanceof Map<?, ?> sectionMap)) {
            throw new IllegalStateException("YAML resource " + path + " has no 'extensions." + profile + ":' section");
        }
        return (Map<String, Object>) sectionMap;
    }

    private Class<?> controllerClassFromProperties() {
        Object declared = properties.get("controller");
        if (declared == null) {
            declared = properties.get("controllerClass");
        }
        if (declared == null) {
            return null;
        }
        if (!(declared instanceof String className) || className.isBlank()) {
            throw new IllegalStateException(
                    "YAML 'controller' / 'controllerClass' value for extension '" + profileName
                            + "' must be a non-blank class name, got: " + declared);
        }
        try {
            return Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Controller class declared in YAML for extension '" + profileName
                            + "' is not on the test classpath: " + className, e);
        }
    }

    /**
     * Register the customer's {@code @RestController} by class — Spring instantiates it via its
     * default constructor and autowires any {@code @Autowired} / {@code @Value} fields against the
     * harness's context. Mutually exclusive with {@link #controller(Object)}.
     *
     * <p>Optional when the YAML loaded through {@link #yaml(String)} declares a
     * {@code controller} / {@code controllerClass} key — the harness falls back to the YAML
     * value if no explicit {@code .controller(...)} is set. An explicit call wins when both are
     * present, which is useful when a test wants to swap in a stub controller.
     */
    public AgentExtensionHarness controller(Class<?> controllerClass) {
        checkControllerUnset();
        this.controllerClass = controllerClass;
        return this;
    }

    /**
     * Register a pre-built controller instance — the harness uses it as-is without Spring
     * autowiring; customer code is responsible for setting any dependencies. The
     * {@code "agentExtension"} bean is still exposed via {@link #agentExtension()} so the test
     * can wire it manually. Mutually exclusive with {@link #controller(Class)}.
     */
    public AgentExtensionHarness controller(Object controllerInstance) {
        checkControllerUnset();
        this.controllerInstance = controllerInstance;
        return this;
    }

    /**
     * Add a key/value to the extension's YAML section view. Available via
     * {@code AgentExtensionV1.getContext().getProfileConfiguration()} and via
     * {@code @Value("${key}")} on the controller. Dots in keys are literal (no nesting).
     */
    public AgentExtensionHarness property(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    /**
     * Add a key/value to the extension's connection-scoped property view (the one returned by
     * {@code AgentExtensionV1.getContext().getConnectionProperties()}). Available via that map
     * <em>and</em> via {@code @Value("${key}")} on the controller — the same resolution surface
     * profile properties use. Dots in keys are literal (no nesting), matching the profile
     * convention.
     *
     * <p><b>Collision with a profile key is logged as an ERROR and the profile wins.</b> If a
     * key appears in both {@link #property(String, Object)} and this method, {@code @Value}
     * resolves to the profile value; the connection map still reports the value supplied here
     * (it is a verbatim view of what the test registered), but it is unreachable through
     * {@code @Value}. See {@link com.workato.agent.extension.api.AgentExtensionContextV1#getConnectionProperties()}.
     */
    public AgentExtensionHarness connection(String key, Object value) {
        connectionProperties.put(key, value);
        return this;
    }

    /**
     * Bulk variant of {@link #connection(String, Object)} — adds every entry of the given map
     * to the connection view. {@code null} and empty maps are no-ops. Existing keys are
     * overwritten; use the single-key method for one-off values.
     */
    public AgentExtensionHarness connection(Map<String, Object> entries) {
        if (entries == null || entries.isEmpty()) {
            return this;
        }
        connectionProperties.putAll(entries);
        return this;
    }

    /**
     * Load connection properties from a YAML resource on the classpath. The resource must
     * contain a {@code connections:} section with an entry whose key equals this harness's
     * profile name; the entry's children become the connection properties. Shape mirrors
     * {@link #yaml(String)} but under a different top-level key so the two kinds of properties
     * stay separate in a single YAML file.
     *
     * <p>Example YAML:
     * <pre>{@code
     * connections:
     *   my-profile:
     *     api.token: abc-123
     *     base-url: https://api.example.com
     * }</pre>
     * <p>
     * Subsequent {@link #connection(String, Object)} calls win over the YAML (same precedence
     * rule {@link #yaml(String)} applies to profile properties).
     */
    public AgentExtensionHarness connectionYaml(String classpathResource) {
        Map<String, Object> section = loadYamlSection(classpathResource, "connections");
        Map<String, Object> merged = new LinkedHashMap<>(section);
        merged.putAll(connectionProperties);
        connectionProperties.clear();
        connectionProperties.putAll(merged);
        return this;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYamlSection(String classpathResource, String topLevelKey) {
        if (classpathResource == null || classpathResource.isBlank()) {
            throw new IllegalArgumentException("YAML resource path must not be blank");
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AgentExtensionHarness.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("YAML resource not found on classpath: " + classpathResource);
            }
            Map<String, Object> parsed = YAML.readValue(in, Map.class);
            Object root = parsed == null ? null : parsed.get(topLevelKey);
            if (!(root instanceof Map<?, ?> rootMap)) {
                throw new IllegalStateException(
                        "YAML resource " + classpathResource + " has no '" + topLevelKey + ":' section");
            }
            Object section = rootMap.get(profileName);
            if (!(section instanceof Map<?, ?> sectionMap)) {
                throw new IllegalStateException(
                        "YAML resource " + classpathResource + " has no '"
                                + topLevelKey + "." + profileName + ":' section");
            }
            return (Map<String, Object>) sectionMap;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read YAML resource " + classpathResource, e);
        }
    }

    /**
     * Load the extension's properties from a YAML resource on the classpath, mirroring the agent's
     * production {@code config.yml} layout. The resource must contain an {@code extensions:}
     * section with an entry whose key equals this harness's profile name — the entry's children
     * become the extension's properties, available via {@code @Value("${key}")} and via
     * {@code AgentExtensionV1.getContext().getProfileConfiguration()}.
     *
     * <p>Example YAML:
     * <pre>{@code
     * extensions:
     *   my-profile:
     *     controller: com.example.MyController
     *     api.endpoint: https://x
     *     timeout-ms: 5000
     * }</pre>
     * <p>
     * The {@code controller} / {@code controllerClass} key is passed through verbatim like any
     * other property (so the configuration view matches production) <em>and</em> is used as the
     * default controller class if the test does not call {@link #controller(Class)} /
     * {@link #controller(Object)} explicitly. An explicit {@code .controller(...)} always wins,
     * which lets a test override the YAML's choice (e.g., with a stub subclass). Subsequent calls
     * to {@link #property(String, Object)} override YAML values.
     */
    public AgentExtensionHarness yaml(String classpathResource) {
        if (classpathResource == null || classpathResource.isBlank()) {
            throw new IllegalArgumentException("YAML resource path must not be blank");
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AgentExtensionHarness.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("YAML resource not found on classpath: " + classpathResource);
            }
            Map<String, Object> parsed = YAML.readValue(in, Map.class);
            Map<String, Object> section = extractExtensionSection(parsed, profileName, classpathResource);
            // Properties already set via .property() take precedence — merge YAML as the baseline.
            Map<String, Object> merged = new LinkedHashMap<>(section);
            merged.putAll(properties);
            properties.clear();
            properties.putAll(merged);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read YAML resource " + classpathResource, e);
        }
        return this;
    }

    /**
     * Register a singleton bean under the given name in the harness's Spring context — for
     * services, mappers, or stubs the controller autowires. Must be called before {@link #start()}
     * (or before the JUnit 5 lifecycle fires {@code beforeAll}).
     */
    public AgentExtensionHarness bean(String name, Object instance) {
        extraBeans.put(name, instance);
        return this;
    }

    /**
     * Register a {@code @Configuration} class whose {@code @Bean} methods join the context.
     */
    public AgentExtensionHarness configuration(Class<?> configurationClass) {
        extraConfigurations.add(configurationClass);
        return this;
    }

    /**
     * Make the given Spring context the parent of the harness's per-endpoint context — the same
     * mechanism the agent uses in production ({@code Server.createRestDispatcher} calls
     * {@code wac.setParent(applicationContext)}). Every bean reachable from the parent — including
     * {@code @Component}s, {@code @Service}s, and anything declared in a {@code @SpringBootTest} /
     * {@code @ContextConfiguration} setup — becomes autowire-visible to the controller, so a
     * test does not have to re-register each stub via {@link #bean(String, Object)}.
     *
     * <p>Precedence: child beans (the controller, {@code "agentExtension"}, and anything added via
     * {@link #bean(String, Object)} / {@link #configuration(Class)}) win over same-named parent
     * beans — standard Spring child-context semantics.
     *
     * <p>Typical pattern — reuse a Spring-Test–managed context already present in the test:
     * <pre>{@code
     * @ExtendWith(SpringExtension.class)
     * @ContextConfiguration(classes = CustomerBeansConfig.class)
     * class MyControllerTest {
     *     @Autowired ApplicationContext parent;
     *
     *     AgentExtensionHarness agent;
     *
     *     @BeforeEach
     *     void setup() {
     *         agent = AgentExtensionHarness.extension("my-profile")
     *                 .controller(MyController.class)
     *                 .parentContext(parent)
     *                 .yaml("extensions/my-profile.yml")
     *                 .start();
     *     }
     *
     *     @AfterEach void tearDown() { agent.stop(); }
     * }
     * }</pre>
     */
    public AgentExtensionHarness parentContext(ApplicationContext parentContext) {
        this.parentContext = parentContext;
        return this;
    }

    /**
     * Supply a pre-built {@link FakeAgentWebhookV1} the controller will see through
     * {@code agent.webhook()}. Useful when the test wants to pre-load stubs or share the
     * fake across several harness instances. If this method is not called, {@link #start()}
     * creates a fresh {@link FakeAgentWebhookV1} with no stubs — the default behaviour of
     * every {@code send} / {@code wakeup} is to return {@code 200 "{}"} on the first attempt.
     */
    public AgentExtensionHarness webhook(FakeAgentWebhookV1 webhook) {
        if (webhook == null) {
            throw new IllegalArgumentException("webhook must not be null");
        }
        this.webhook = webhook;
        this.externalWebhook = true;
        return this;
    }

    /**
     * Build the Spring context and the {@link MockMvc} instance. Idempotent — subsequent calls
     * return the same instance. Called automatically if the harness is used via
     * {@code @RegisterExtension}.
     */
    public AgentExtensionHarness start() {
        if (mockMvc != null) {
            return this;
        }
        if (controllerClass == null && controllerInstance == null) {
            // Fall back to the YAML's own controller declaration — same key the agent's
            // ExtensionConfigParser reads in production (controller / controllerClass).
            // An explicit .controller(...) call wins over this.
            controllerClass = controllerClassFromProperties();
        }
        if (controllerClass == null && controllerInstance == null) {
            throw new IllegalStateException(
                    "A controller must be available — call .controller(Class|Object), or load a YAML " +
                            "whose extensions." + profileName + " section carries a 'controller' / 'controllerClass' key");
        }
        if (webhook == null) {
            webhook = new FakeAgentWebhookV1();
        }
        else if (!externalWebhook) {
            // We are re-entering start() on the same harness instance (class-level
            // @RegisterExtension re-run, manual restart, …). Clear the carried-over call log /
            // stubs so the next test sees a fresh fake; the reference itself is preserved so
            // anything the test captured as `harness.webhook()` keeps working. An externally
            // supplied webhook (via .webhook(FakeAgentWebhookV1)) is left untouched — its
            // lifecycle belongs to the caller.
            webhook.reset();
        }

        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        // @EnableWebMvc constructs the ResourceHandlerMapping, which asserts a non-null
        // ServletContext at refresh time. In the production agent that comes from Jetty; here a
        // MockServletContext satisfies the same contract without an embedded server.
        ctx.setServletContext(new MockServletContext());
        if (parentContext != null) {
            // Mirrors Server.createRestDispatcher in agent-core: wac.setParent(applicationContext).
            // Beans in the parent become autowire-visible to the controller; child beans with the
            // same name shadow the parent's — standard Spring child-context semantics.
            ctx.setParent(parentContext);
        }
        // Only the profile goes into the Environment's PropertySources. Plain @Value on a bean
        // field goes through Spring's default placeholder resolution, which walks the Environment
        // — so plain-field @Value is profile-only by construction. The connection payload is
        // reached via two side-channels:
        //   (a) ProfileAndConnectionAutowireCandidateResolver — makes @Value Supplier<T> on
        //       autowired targets (fields, constructor / setter params) see both sources.
        //   (b) ProfileAndConnectionValueArgumentResolver — makes @Value on handler method
        //       params see both sources, plain or Supplier-shaped.
        // Collision rule (profile wins + ERROR log) is applied inside PropertyResolution.
        ctx.getEnvironment().getPropertySources().addFirst(
                EndpointPropertySource.forProfile(profileName, properties));
        PropertyResolution resolution = new PropertyResolution(profileName, properties, connectionProperties);
        ctx.addBeanFactoryPostProcessor(new HarnessBeanRegistrar(resolution));
        ctx.register(MvcConfig.class);
        for (Class<?> cfg : extraConfigurations) {
            ctx.register(cfg);
        }
        ctx.refresh();

        this.webContext = ctx;
        // The default request bakes the production servlet mapping in: a controller declared
        // with @RequestMapping("/op") answers requests to /ext/{profile}/op because Spring MVC
        // computes the handler path as (requestURI - contextPath - servletPath).
        this.mockMvc = MockMvcBuilders.webAppContextSetup(ctx)
                .defaultRequest(MockMvcRequestBuilders.get("/").servletPath("/ext/" + profileName))
                .build();
        return this;
    }

    /**
     * Shut down the Spring context. Idempotent.
     *
     * <p>Does <b>not</b> clear the {@link FakeAgentWebhookV1} call log — a test that captures
     * state after {@code stop()} (e.g., "no deliveries arrived after shutdown") needs the log to
     * stay intact. The next {@link #start()} on the same harness instance clears the fake's state
     * before the controller sees it; see the comment in {@code start()}.
     */
    public void stop() {
        if (webContext != null) {
            webContext.close();
            webContext = null;
        }
        mockMvc = null;
    }

    /**
     * The Spring MVC {@link MockMvc} the test uses to issue requests.
     */
    public MockMvc mockMvc() {
        ensureStarted();
        return mockMvc;
    }

    /**
     * The {@link AgentExtensionV1} instance seen by the controller — exposed for assertions.
     */
    public AgentExtensionV1 agentExtension() {
        ensureStarted();
        return webContext.getBean("agentExtension", AgentExtensionV1.class);
    }

    /**
     * The {@link FakeAgentWebhookV1} the controller receives through
     * {@code agent.webhook()} — exposed for stubbing responses and asserting against the
     * captured call log without an unchecked cast.
     */
    public FakeAgentWebhookV1 webhook() {
        ensureStarted();
        return webhook;
    }

    /**
     * The {@link com.workato.agent.extension.api.AgentExtensionContextV1.ConnectionProperties}
     * the controller sees through {@code agent.getContext().getConnectionProperties()} —
     * convenience accessor equivalent to that chain. Exposed as a shortcut for tests that
     * assert on the fingerprint ({@code contentHash()}) or on specific entries.
     */
    public com.workato.agent.extension.api.AgentExtensionContextV1.ConnectionProperties connectionProperties() {
        ensureStarted();
        return agentExtension().getContext().getConnectionProperties();
    }

    /**
     * The web application context underlying the harness — exposed for advanced wiring in tests.
     */
    public AnnotationConfigWebApplicationContext webApplicationContext() {
        ensureStarted();
        return webContext;
    }

    /**
     * The URL path a MockMvc request should target to hit the given controller path, including
     * the production {@code /ext/{profileName}} prefix. Use with
     * {@code mockMvc.perform(get(agent.urlFor("/op")))}.
     */
    public String urlFor(String path) {
        if (path == null || path.isEmpty()) {
            return "/ext/" + profileName;
        }
        return "/ext/" + profileName + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Resolve a URL from a zero-arg controller method reference — reads the method's Spring web
     * annotation ({@code @GetMapping(...)}, etc.) at runtime, prepends the production
     * {@code /ext/{profileName}} prefix, and returns the full path. Avoids repeating the route
     * string already declared on the controller.
     *
     * <pre>{@code agent.urlFor(EchoController::echo)  // → "/ext/my-profile/echo"}</pre>
     */
    public <C, R> String urlFor(Ref0<C, R> methodRef) {
        return urlFor(ControllerMethodResolver.resolve(methodRef, new Object[0]).controllerPath());
    }

    /**
     * Resolve a URL from a one-arg controller method reference, substituting each
     * {@code @PathVariable} in the route template with the matching positional value. Non-path
     * parameters ({@code @RequestBody}, {@code @RequestParam}, …) are skipped — supply only path
     * variable values via {@code pathVars}.
     *
     * <pre>{@code agent.urlFor(UpperCasingController::upper, "world")  // → "/ext/upper/upper/world"}</pre>
     *
     * @apiNote {@code pathVars} must carry exactly one value per {@code @PathVariable} parameter,
     * in declaration order. <em>Extra</em> values beyond the number of path variables are
     * silently ignored — the resolver only consumes the first N where N is the number of
     * {@code @PathVariable} parameters on the method. This is deliberate so a
     * {@code Ref1} method reference whose one declared parameter is actually
     * {@code @RequestBody} (and the path variable count is zero) still compiles against
     * the same overload. Supplying <em>fewer</em> values than there are path variables
     * throws {@link IllegalArgumentException}.
     */
    public <C, A, R> String urlFor(Ref1<C, A, R> methodRef, Object... pathVars) {
        return urlFor(ControllerMethodResolver.resolve(methodRef, pathVars).controllerPath());
    }

    /**
     * Build a {@link MockHttpServletRequestBuilder} from a zero-arg controller method reference.
     * HTTP method is inferred from the controller annotation ({@code @GetMapping} → GET, etc.);
     * the URL is built as in {@link #urlFor(Ref0)}. The returned builder can be further decorated
     * ({@code .content(...)}, {@code .header(...)}) and passed to {@code mockMvc().perform(...)}.
     *
     * <pre>{@code mockMvc().perform(agent.request(EchoController::echo))}</pre>
     */
    public <C, R> MockHttpServletRequestBuilder request(Ref0<C, R> methodRef) {
        ControllerMethodResolver.Resolved r = ControllerMethodResolver.resolve(methodRef, new Object[0]);
        return MockMvcRequestBuilders.request(r.httpMethod(), urlFor(r.controllerPath()));
    }

    /**
     * Build a {@link MockHttpServletRequestBuilder} from a one-arg controller method reference,
     * substituting {@code @PathVariable} placeholders with {@code pathVars}. See
     * {@link #urlFor(Ref1, Object...)} for the substitution rule and the {@code @apiNote} on
     * how extra path-var values are handled.
     *
     * <pre>{@code mockMvc().perform(agent.request(UpperCasingController::upper, "world"))}</pre>
     */
    public <C, A, R> MockHttpServletRequestBuilder request(Ref1<C, A, R> methodRef, Object... pathVars) {
        ControllerMethodResolver.Resolved r = ControllerMethodResolver.resolve(methodRef, pathVars);
        return MockMvcRequestBuilders.request(r.httpMethod(), urlFor(r.controllerPath()));
    }

    /**
     * The configured profile name.
     */
    public String profileName() {
        return profileName;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        start();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        stop();
    }

    // ---- JUnit 5 lifecycle integration -------------------------------------------------------

    // The harness implements both class-level (BeforeAll/AfterAll) and instance-level
    // (BeforeEach/AfterEach) callbacks. JUnit 5 only invokes the ones that match the registration
    // site: a static @RegisterExtension field triggers class-level, a non-static one triggers
    // instance-level — the latter is required for @Nested test classes, where static fields are
    // not allowed. Both call start() / stop(), both are idempotent, so overlapping invocations
    // (e.g., mixing BeforeAll with BeforeEach) are safe.

    @Override
    public void beforeEach(ExtensionContext context) {
        start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        stop();
    }

    private void checkControllerUnset() {
        if (controllerClass != null || controllerInstance != null) {
            throw new IllegalStateException("controller(...) may be called only once per harness");
        }
    }

    private void ensureStarted() {
        if (mockMvc == null) {
            throw new IllegalStateException("Harness has not been started — call start() or use @RegisterExtension");
        }
    }


    // ---- internals ---------------------------------------------------------------------------

    /**
     * Serializable zero-arg method reference target. Marker SAM — customers never implement it
     * explicitly; it is the type a method reference like {@code EchoController::echo} lands in
     * when passed to {@link #urlFor(Ref0)} / {@link #request(Ref0)}.
     */
    @FunctionalInterface
    public interface Ref0<C, R> extends Serializable {
        R apply(C controller);
    }

    /**
     * Serializable one-arg method reference target. See {@link Ref0} for background.
     */
    @FunctionalInterface
    public interface Ref1<C, A, R> extends Serializable {
        R apply(C controller, A arg);
    }

    /**
     * Serializable two-arg method reference target. Lets a method reference whose target has two
     * non-controller parameters (e.g., {@code @RequestBody} + {@code @RequestHeader}) land on a
     * SAM of the correct arity, so {@link DeployedAgentLauncher.RunningAgent#httpPost} and
     * {@link DeployedAgentLauncher.RunningAgent#requestFor} overloads can accept it. The
     * resolver never calls {@code apply(...)} — only the serialized lambda metadata matters.
     * See {@link Ref0} for background.
     */
    @FunctionalInterface
    public interface Ref2<C, A1, A2, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2);
    }

    /**
     * Serializable three-arg method reference target. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref3<C, A1, A2, A3, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3);
    }

    /**
     * Serializable four-arg method reference target. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref4<C, A1, A2, A3, A4, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3, A4 arg4);
    }

    /**
     * Serializable five-arg method reference target. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref5<C, A1, A2, A3, A4, A5, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5);
    }

    /**
     * Serializable six-arg method reference target. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref6<C, A1, A2, A3, A4, A5, A6, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6);
    }

    /**
     * Serializable seven-arg method reference target. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref7<C, A1, A2, A3, A4, A5, A6, A7, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6, A7 arg7);
    }

    /**
     * Serializable eight-arg method reference target. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref8<C, A1, A2, A3, A4, A5, A6, A7, A8, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6, A7 arg7, A8 arg8);
    }

    /**
     * Serializable nine-arg method reference target. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref9<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6, A7 arg7, A8 arg8, A9 arg9);
    }

    /**
     * Serializable ten-arg method reference target. Upper bound of the arity ladder — controllers
     * with more than ten declared parameters are rare enough that the fallback is to build the
     * {@link java.net.http.HttpRequest} with the string form instead. See {@link Ref2} for background.
     */
    @FunctionalInterface
    public interface Ref10<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> extends Serializable {
        R apply(C controller, A1 arg1, A2 arg2, A3 arg3, A4 arg4, A5 arg5, A6 arg6, A7 arg7, A8 arg8, A9 arg9, A10 arg10);
    }

    /**
     * Minimal MVC config for the harness context — bootstraps HandlerMapping / DispatcherServlet
     * plumbing. No {@code @ComponentScan}: the harness explicitly registers the one controller
     * and only the beans the test asks for.
     */
    @Configuration
    @EnableWebMvc
    static class MvcConfig {

        /**
         * Registers a {@link PropertySourcesPlaceholderConfigurer} so unresolvable placeholders
         * throw instead of silently leaving the literal {@code ${key}} in the field value. Matches
         * the production agent's wiring and makes the harness's "plain field = profile only"
         * visibility rule enforceable at bean-creation time rather than discoverable at runtime.
         */
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /**
     * Registers the per-endpoint beans every extension's context needs: the controller (by class
     * or by instance), the {@code "agentExtension"} singleton, plus any extra beans supplied via
     * {@link #bean(String, Object)}. Mirrors the agent's own {@code EndpointBeanRegistrar} in shape.
     */
    private final class HarnessBeanRegistrar implements BeanDefinitionRegistryPostProcessor {

        private final PropertyResolution resolution;

        HarnessBeanRegistrar(PropertyResolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (controllerClass != null) {
                registry.registerBeanDefinition("controller", new AnnotatedGenericBeanDefinition(controllerClass));
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // AnnotationConfigUtils installed a ContextAnnotationAutowireCandidateResolver during
            // the registry phase; replace it with our subclass so @Value Supplier<T> on autowired
            // targets (fields, ctor / setter params) can see the connection payload. Plain @Value
            // flows through the superclass unchanged — Environment → profile only.
            if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
                dlbf.setAutowireCandidateResolver(new ProfileAndConnectionAutowireCandidateResolver(resolution));
            }
            // BeanPostProcessor that prepends our resolver to RequestMappingHandlerAdapter after
            // the adapter completes afterPropertiesSet, so @Value on handler method params lands
            // on our resolver first (both sources, plain or Supplier-shaped).
            beanFactory.addBeanPostProcessor(new ArgumentResolverRegistrar(
                    new ProfileAndConnectionValueArgumentResolver(resolution)));

            beanFactory.registerSingleton("agentExtension",
                    new FakeAgentExtension(profileName, properties, connectionProperties, webhook));
            if (controllerInstance != null) {
                beanFactory.registerSingleton("controller", controllerInstance);
            }
            for (Map.Entry<String, Object> e : extraBeans.entrySet()) {
                beanFactory.registerSingleton(e.getKey(), e.getValue());
            }
        }
    }
}
