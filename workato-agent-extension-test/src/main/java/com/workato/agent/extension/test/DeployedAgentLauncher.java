/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.util.*;

/**
 * In-process launcher for a deployed agent and ONE extension profile.
 *
 * <p><strong>Not for CI.</strong> Every test driven by {@code DeployedAgentLauncher} requires
 * a <strong>deployed and activated</strong> agent distribution on disk (cert.pem / cert.key
 * signed by Workato for a real workspace, matching gateway environment, …). That is a
 * per-developer-machine setup, not a reproducible CI artifact. Keep these tests
 * {@code @Disabled} by default; run them manually from the IDE when verifying end-to-end
 * behaviour against the real agent, or when reproducing a mysterious issue that only
 * surfaces inside a full agent process. For every scenario covered by Spring MVC wiring
 * (routing, {@code @Value} injection, autowired beans, …) prefer {@code AgentExtensionHarness}
 * — it runs in milliseconds, needs no on-disk dist, and is safe on CI.
 *
 * <p>The runner is constructed with the extension's profile name
 * ({@link #create(String, Path)}). All default-targeted builder methods —
 * {@link #controllerClass(Class)}, {@link #property(String, Object)},
 * {@link #properties(Map)}, {@link #yaml(String)} — then configure that profile without you
 * having to repeat the name. Foreign profiles already declared in the base {@code config.yml}
 * can still be tweaked via {@link #extensionProperty(String, String, Object)} /
 * {@link #extensionProperties(String, Map)}.
 *
 * <h2>Classloader layout</h2>
 * One {@link URLClassLoader} whose parent is {@link ClassLoader#getPlatformClassLoader()}.
 * This isolates the agent from the surrounding test-runner classpath (which, for consumers of
 * extension-test, drags in Spring, Jackson and friends that would otherwise collide with the
 * ones in {@code lib/}). The extension's code source is a URL inside the same loader as the
 * agent's jars — one copy of every class, no cross-loader {@code ClassCastException}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create("my-profile")
 *         .controllerClass(MyController.class)
 *         .property("api-url", "https://example.com")
 *         .property("timeout-ms", 5000)
 *         .start()) {
 *     HttpResponse<String> res = agent.httpGet(MyController::list);
 *     ...
 * }
 * }</pre>
 *
 * <p>Or load the profile baseline from an existing YAML fixture and override a few keys:
 * <pre>{@code
 * try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create("my-profile")
 *         .yaml("extensions/echo.yml")                 // sets controllerClass + greeting from YAML
 *         .property("greeting", "overridden")          // overrides the YAML value
 *         .start()) {
 *     ...
 * }
 * }</pre>
 *
 * <h2>Debugging</h2>
 * The agent runs in the current JVM, so launching the test / main class from an IDE's debugger
 * is enough — breakpoints inside both {@code agent-core} / {@code agent-launcher} code and the
 * extension's own code trigger normally. The extension classes are loaded by the agent's
 * {@link URLClassLoader}, so the {@link Class} instance that fires the breakpoint is distinct
 * from the one the test code references, but source-line breakpoints are unaffected.
 */
public final class DeployedAgentLauncher {

    private static final String MAIN_CLASS = "com.workato.agent.Main";
    private static final String LAUNCHER_CLASS = "com.workato.agent.Launcher";
    private static final String OPTION_CONF = "--conf";
    private static final String OPTION_CONNECTOR_LISTEN = "--connector-listen";
    private static final String OPTION_KEY = "--key";
    private static final String OPTION_PEM = "--pem";
    private static final String OPTION_KEY_ID = "--key-id";
    private static final String OPTION_CERT = "--cert";
    private static final String OPTION_WORKATO_PROPERTIES = "--workato-properties";
    private static final String WORKATO_PROPERTIES_RESOURCE = "workato.properties";
    private static final String GATEWAY_CA_PROPERTY = "gateway.ca.file";
    private static final String CERT_KEY_FILE = "cert.key";
    private static final String CERT_PEM_FILE = "cert.pem";
    private static final String CERT_ID_FILE = "cert.id";
    private static final String LISTEN_HOST = "127.0.0.1";
    private static final String EXTENSIONS_SECTION = "extensions";
    private static final String CONTROLLER_CLASS_KEY = "controllerClass";
    private static final String CONTROLLER_KEY = "controller";
    private static final String GATEWAY_CONNECTED_MARKER = "Connected to gateway";
    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(DeployedAgentLauncher.class);

    private static final Path BUILT_INSTALL_RELATIVE = Path.of("modules", "agent-launcher", "build", "install");

    private final String profileName;
    private final Path distPath;
    private final Path confPath;
    private final List<URL> extensionUrls = new ArrayList<>();
    private final List<String> arguments = new ArrayList<>();
    private final Map<String, String> systemProperties = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> extensionProfiles = new LinkedHashMap<>();
    private Integer port;
    private Path baseConfigPath;

    private DeployedAgentLauncher(String profileName, Path distPath, Path confPath) {
        this.profileName = profileName;
        this.distPath = distPath;
        this.confPath = confPath;
        extensionProfiles.put(profileName, new LinkedHashMap<>());
    }

    /**
     * Creates a launcher for the extension profile with the given name against the agent at
     * {@code path}. Resolution order:
     *
     * <ol>
     *     <li>If {@code path/lib/} exists, {@code path} is treated as a deployed agent — jars
     *         come from {@code path/lib/} (and {@code path/lib_ext/}), activation certificates
     *         and the base {@code config.yml} come from {@code path/conf/}.</li>
     *     <li>Otherwise the launcher falls back to the <strong>locally-built</strong> agent
     *         produced by {@code ./gradlew :agent-launcher:installDist} (auto-discovered under
     *         {@code modules/agent-launcher/build/install/*}) for jars, and still reads
     *         activation certificates and the base {@code config.yml} from {@code path/conf/}.
     *         Useful when iterating on your own {@code agent-core} / {@code extension-api}
     *         source while activating against an existing on-disk cert.</li>
     *     <li>If neither is available, throws {@link IllegalStateException}.</li>
     * </ol>
     *
     * <p>Both arguments are mandatory — the runner does not consult system properties or
     * environment variables. All default-targeted builder methods
     * ({@link #controllerClass(Class)}, {@link #property(String, Object)},
     * {@link #properties(Map)}, {@link #yaml(String)}) configure this profile.
     */
    public static DeployedAgentLauncher create(String profileName, Path path) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Extension profile name must not be blank");
        }
        if (path == null) {
            throw new IllegalArgumentException("Agent path must not be null");
        }
        Path distPath;
        if (Files.isDirectory(path.resolve("lib"))) {
            distPath = path;
        }
        else {
            distPath = detectLocalBuildDist().orElseThrow(() -> new IllegalStateException(
                    "No deployed agent at " + path + " (no lib/ subdirectory) and no local build found"
                            + " under " + BUILT_INSTALL_RELATIVE + ". Point `path` at a deployed agent"
                            + " root, or run `./gradlew :agent-launcher:installDist` first."));
        }
        return new DeployedAgentLauncher(profileName, distPath, path);
    }

    private static Optional<Path> detectLocalBuildDist() {
        Path projectRoot = findProjectRoot();
        if (projectRoot == null) {
            return Optional.empty();
        }
        Path installRoot = projectRoot.resolve(BUILT_INSTALL_RELATIVE);
        if (!Files.isDirectory(installRoot)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(installRoot, Files::isDirectory)) {
            for (Path candidate : stream) {
                if (Files.isDirectory(candidate.resolve("lib"))) {
                    return Optional.of(candidate);
                }
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    private static Path findProjectRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Default-profile builder methods — target the profile passed to create(...).
    // ---------------------------------------------------------------------------------------

    /**
     * The agent's {@code AgentSSLContext.load} looks for activation certificates at
     * {@code Paths.get("conf/cert.*")} — relative paths resolved against the JVM's working
     * directory. The production {@code run.sh} works around this by {@code cd $WORKATO_AGENT_HOME}
     * before launching; the test JVM running {@code DeployedAgentLauncher} has no such benefit, so without
     * explicit absolute paths the agent silently starts without client certificates and never
     * connects to the Workato gateway (and the cloud never sees it as online).
     *
     * <p>This method injects {@code --key}, {@code --pem} and (if present) {@code --key-id} into
     * the launcher's argument list, pointing at the files inside {@code {dist}/conf/}. Explicit
     * user-supplied flags take precedence.
     */
    private static void injectAbsoluteCertPaths(Path confRoot, List<String> effectiveArgs) {
        Path conf = confRoot.resolve("conf");
        if (!Files.isDirectory(conf)) {
            return;
        }
        maybeAddFileArg(effectiveArgs, OPTION_KEY, conf.resolve(CERT_KEY_FILE));
        maybeAddFileArg(effectiveArgs, OPTION_PEM, conf.resolve(CERT_PEM_FILE));
        // A loose conf/workato.properties fully overrides the jar-bundled one
        // (agent.version, agent.default.gateway, gateway.ca.file, …) — the agent's
        // Config.init only reads the jar resource by default, so without this flag the loose
        // file is silently ignored by every consumer except our own trust-CA extractor.
        maybeAddFileArg(effectiveArgs, OPTION_WORKATO_PROPERTIES, conf.resolve(WORKATO_PROPERTIES_RESOURCE));
        Path keyId = conf.resolve(CERT_ID_FILE);
        if (Files.isRegularFile(keyId) && !containsOption(effectiveArgs, OPTION_KEY_ID)) {
            try {
                String id = Files.readString(keyId).trim();
                if (!id.isEmpty()) {
                    effectiveArgs.add(OPTION_KEY_ID);
                    effectiveArgs.add(id);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + keyId, e);
            }
        }
    }

    private static void maybeAddFileArg(List<String> effectiveArgs, String option, Path file) {
        if (!Files.isRegularFile(file) || containsOption(effectiveArgs, option)) {
            return;
        }
        effectiveArgs.add(option);
        effectiveArgs.add(file.toAbsolutePath().toString());
    }

    private static boolean containsOption(List<String> args, String option) {
        for (String arg : args) {
            if (option.equals(arg) || arg.startsWith(option + "=")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readYamlRoot(Path path) {
        try {
            Object parsed = YAML.readValue(path.toFile(), Object.class);
            if (parsed == null) {
                return new LinkedHashMap<>();
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Base config " + path + " is not a YAML mapping at the root");
            }
            return new LinkedHashMap<>((Map<String, Object>) map);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to read base config " + path, e);
        }
    }

    private static void cleanupAfterFailure(URLClassLoader cl, ClassLoader previousTccl,
                                            Path generatedConfig, PrintStream originalStdout) {
        Thread.currentThread().setContextClassLoader(previousTccl);
        System.setOut(originalStdout);
        try {
            cl.close();
        } catch (IOException ignored) {
        }
        deleteQuietly(generatedConfig);
    }

    // ---------------------------------------------------------------------------------------
    // Foreign-profile builder methods — target a different profile by explicit name,
    // for tweaking profiles declared in the base config.yml without rewriting them.
    // ---------------------------------------------------------------------------------------

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    /**
     * Collects {@code *.jar} entries in {@code dir} and (one level deep) in any immediate
     * subdirectories. Mirrors {@code Launcher.getClasspath} which treats {@code lib/<module>/}
     * as a container for connector-specific jars.
     */
    private static void collectJars(Path dir, Set<URL> target) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        collectJarsFlat(dir, target);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path subdir : stream) {
                collectJarsFlat(subdir, target);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list subdirectories of " + dir, e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Agent-wide builder methods.
    // ---------------------------------------------------------------------------------------

    private static void collectJarsFlat(Path dir, Set<URL> target) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : stream) {
                target.add(jar.toAbsolutePath().toUri().toURL());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + dir + " for jar files", e);
        }
    }

    private static URL codeSourceOf(Class<?> cls) {
        ProtectionDomain pd = cls.getProtectionDomain();
        CodeSource cs = pd == null ? null : pd.getCodeSource();
        URL location = cs == null ? null : cs.getLocation();
        if (location == null) {
            throw new IllegalArgumentException(
                    "Cannot determine code source URL for " + cls.getName()
                            + " (loaded by " + cls.getClassLoader() + ")");
        }
        return location;
    }

    /**
     * Loads an extension profile body from a YAML classpath resource shaped like
     * {@code extensions.{profileName}: {...}}. Returns the map under that profile key.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadExtensionProfile(String classpathResource, String profileName) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("YAML resource not found on classpath: " + classpathResource);
            }
            Map<String, Object> root = YAML.readValue(in, Map.class);
            Map<String, Object> extensions = root == null ? null : (Map<String, Object>) root.get(EXTENSIONS_SECTION);
            if (extensions == null) {
                throw new IllegalStateException("YAML resource " + classpathResource + " has no 'extensions:' section");
            }
            Map<String, Object> section = (Map<String, Object>) extensions.get(profileName);
            if (section == null) {
                throw new IllegalStateException(
                        "YAML resource " + classpathResource + " has no 'extensions." + profileName + ":' section");
            }
            return section;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + classpathResource, e);
        }
    }

    /**
     * Picks a free TCP port by binding {@code ServerSocket(0)} and immediately releasing it.
     * Standard race window applies — another process can claim the port before the agent
     * binds to it, but the OS does not reuse ports immediately so collisions are rare.
     */
    public static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate a free port", e);
        }
    }

    private static HttpResponse<String> httpSend(HttpRequest req) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        try {
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("HTTP " + req.method() + " " + req.uri() + " failed", e);
        }
    }

    private static HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10));
    }

    private static void appendCertArg(List<String> effectiveArgs, List<Path> trustPaths) {
        StringBuilder joined = new StringBuilder();
        for (Path p : trustPaths) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(p.toAbsolutePath());
        }
        effectiveArgs.add(OPTION_CERT);
        effectiveArgs.add(joined.toString());
    }

    /**
     * Overrides the agent's gateway trust-store by injecting {@code --cert /abs/f1|/abs/f2}
     * into the launcher args when loose trust PEMs are present under {@code path/conf/}:
     * {@code path/conf/workato.properties} is read, each PEM listed under
     * {@code gateway.ca.file} is resolved as a sibling of {@code cert.pem}, and those files
     * are passed in. Two scenarios use this:
     *
     * <ul>
     *     <li><strong>Pointing an otherwise self-consistent dist at a different gateway
     *         environment</strong> without rebuilding its jars — drop
     *         {@code workato.properties} + the named {@code root_ca_*.pem} files into
     *         {@code conf/}.</li>
     *     <li><strong>Local-build fallback mode</strong> ({@code path/lib/} absent): the jars
     *         come from the locally built agent whose bundled trust store may not match the
     *         gateway the external cert is bound to; the loose PEMs supply the right one.</li>
     * </ul>
     *
     * <p>If no loose {@code workato.properties} is present this method is a no-op — the
     * agent's own classloader resolves the trust-store from its jars. An explicit
     * {@code --cert} in {@code argument(...)} is respected and skips this logic entirely.
     */
    private void injectExternalTrustCerts(List<String> effectiveArgs) {
        if (containsOption(effectiveArgs, OPTION_CERT)) {
            return;
        }
        List<Path> fromConf = findLooseTrustCertsInConf();
        if (!fromConf.isEmpty()) {
            appendCertArg(effectiveArgs, fromConf);
        }
    }

    private List<Path> findLooseTrustCertsInConf() {
        Path conf = confPath.resolve("conf");
        if (!Files.isDirectory(conf)) {
            return List.of();
        }
        Path propsFile = conf.resolve(WORKATO_PROPERTIES_RESOURCE);
        if (!Files.isRegularFile(propsFile)) {
            return List.of();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsFile)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + propsFile, e);
        }
        String caList = props.getProperty(GATEWAY_CA_PROPERTY);
        if (caList == null || caList.isBlank()) {
            return List.of();
        }
        List<Path> pems = new ArrayList<>();
        for (String caName : caList.split("\\|")) {
            String trimmed = caName.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path pem = conf.resolve(trimmed);
            if (Files.isRegularFile(pem)) {
                pems.add(pem);
            }
        }
        return pems;
    }

    /**
     * Sets the controller class for this runner's extension profile. Type-safe equivalent of
     * {@code property("controllerClass", controllerClass.getName())}.
     */
    public DeployedAgentLauncher controllerClass(Class<?> controllerClass) {
        if (controllerClass == null) {
            throw new IllegalArgumentException("Controller class must not be null");
        }
        return property(CONTROLLER_CLASS_KEY, controllerClass.getName());
    }

    /**
     * Adds or overrides a single property on this runner's extension profile.
     */
    public DeployedAgentLauncher property(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Property key must not be blank");
        }
        extensionProfiles.get(profileName).put(key, value);
        return this;
    }

    /**
     * Merges the given entries into this runner's extension profile, overriding any
     * pre-existing keys. Keys not present in {@code entries} are preserved.
     */
    public DeployedAgentLauncher properties(Map<String, Object> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("Properties map must not be null");
        }
        extensionProfiles.get(profileName).putAll(entries);
        return this;
    }

    /**
     * Loads this runner's extension profile from a YAML classpath resource shaped like
     * {@code extensions.{profileName}: {...}} (the same shape production {@code config.yml}
     * uses) and merges it into whatever has been configured so far. Later calls to
     * {@link #property(String, Object)} / {@link #properties(Map)} / {@link #controllerClass(Class)}
     * override individual keys loaded from the YAML.
     */
    public DeployedAgentLauncher yaml(String classpathResource) {
        return properties(loadExtensionProfile(classpathResource, profileName));
    }

    /**
     * Adds or overrides a single property on an arbitrary extension profile — used to tweak
     * profiles that live in the base {@code config.yml} (foreign profiles) without redefining
     * them wholesale. For this runner's own profile, prefer {@link #property(String, Object)}.
     */
    public DeployedAgentLauncher extensionProperty(String profileName, String key, Object value) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Extension profile name must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Property key must not be blank");
        }
        extensionProfiles.computeIfAbsent(profileName, k -> new LinkedHashMap<>()).put(key, value);
        return this;
    }

    /**
     * Bulk variant of {@link #extensionProperty(String, String, Object)}.
     */
    public DeployedAgentLauncher extensionProperties(String profileName, Map<String, Object> overrides) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("Extension profile name must not be blank");
        }
        if (overrides == null) {
            throw new IllegalArgumentException("Overrides map for '" + profileName + "' must not be null");
        }
        extensionProfiles.computeIfAbsent(profileName, k -> new LinkedHashMap<>()).putAll(overrides);
        return this;
    }

    /**
     * Adds the on-disk code source (classes directory or jar) of each given class as an
     * extension classpath entry. The classes must already be loadable from the surrounding
     * JVM's classpath; this call captures where their bytecode lives so the agent's
     * isolated classloader can load them too.
     */
    public DeployedAgentLauncher extensionCodeSource(Class<?>... classes) {
        for (Class<?> cls : classes) {
            extensionUrls.add(codeSourceOf(cls));
        }
        return this;
    }

    /**
     * Adds explicit paths (classes directories or jar files) as extension classpath entries.
     */
    public DeployedAgentLauncher extensionCodeSource(Path... paths) {
        for (Path p : paths) {
            try {
                extensionUrls.add(p.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Cannot convert path to URL: " + p, e);
            }
        }
        return this;
    }

    /**
     * Appends arguments forwarded to the agent's {@code Launcher.start(args)} — the same
     * strings {@code run.sh} would pass to {@code java -jar agent-launcher.jar}.
     */
    public DeployedAgentLauncher argument(String... args) {
        Collections.addAll(this.arguments, args);
        return this;
    }

    /**
     * Sets a JVM system property before the agent starts.
     */
    public DeployedAgentLauncher systemProperty(String key, String value) {
        systemProperties.put(key, value);
        return this;
    }

    /**
     * Opens direct HTTP access to the agent from the test JVM on an explicit TCP port, bound
     * to {@code 127.0.0.1}. Injects {@code --connector-listen http://127.0.0.1:{port}} into
     * the launcher args so the agent exposes {@code /ext/{profile}/...} locally.
     *
     * <p><strong>Mutually exclusive with the Workato gateway connection.</strong> Opening
     * local access puts the agent into <strong>connector mode</strong>:
     * {@code @Conditional(AgentEnabled.class)} on every outbound-gateway bean in
     * {@code Application} evaluates {@code --connector-listen} as present and suppresses them.
     * As a result the agent <strong>will not connect to the Workato cloud gateway</strong>
     * (Workato will not see the agent as online). Omit both {@code openLocalAccess(...)} /
     * {@link #openLocalAccess()} to run in full gateway mode instead.
     *
     * <p>Read the chosen port after startup via {@link RunningAgent#port()}.
     */
    public DeployedAgentLauncher openLocalAccess(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be in the range [1, 65535], got " + port);
        }
        this.port = port;
        return this;
    }

    /**
     * Opens direct HTTP access on an auto-allocated free port — shortcut for
     * {@code openLocalAccess(findFreePort())}.
     *
     * <p><strong>Mutually exclusive with the Workato gateway connection:</strong> see
     * {@link #openLocalAccess(int)} for the full trade-off. The agent will serve local HTTP
     * but will not connect to the Workato cloud gateway.
     */
    public DeployedAgentLauncher openLocalAccess() {
        return openLocalAccess(findFreePort());
    }

    /**
     * Base {@code config.yml} to merge the generated extension profile(s) into. Defaults to
     * {@code {distPath}/conf/config.yml} when that file exists; otherwise starts from an empty
     * root.
     */
    public DeployedAgentLauncher baseConfig(Path path) {
        this.baseConfigPath = path;
        return this;
    }

    public RunningAgent start() {
        Path libs = resolveDistPath();
        addInferredExtensionSources();
        List<URL> composite = buildClasspath(libs);
        systemProperties.forEach(System::setProperty);

        List<String> effectiveArgs = new ArrayList<>(arguments);
        int effectivePort = -1;
        if (port != null) {
            rejectConflictingArg(OPTION_CONNECTOR_LISTEN,
                    "openLocalAccess(...) injects --connector-listen; remove --connector-listen from argument(...).");
            effectivePort = port;
            effectiveArgs.add(OPTION_CONNECTOR_LISTEN);
            effectiveArgs.add("http://" + LISTEN_HOST + ":" + effectivePort);
        }

        rejectConflictingArg(OPTION_CONF,
                "An extension profile is always generated; remove --conf from argument(...)"
                        + " or use baseConfig(Path) to specify the template to merge into.");
        Path generatedConfig = writeMergedConfig(confPath);
        effectiveArgs.add(OPTION_CONF);
        effectiveArgs.add(generatedConfig.toString());

        injectAbsoluteCertPaths(confPath, effectiveArgs);
        injectExternalTrustCerts(effectiveArgs);

        logStartupSummary(effectiveArgs, effectivePort);

        URLClassLoader agentClassLoader = new URLClassLoader(
                composite.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader()
        );

        // Tee System.out BEFORE triggering agent's logback initialisation, so the
        // ConsoleAppender it constructs captures our teed stream as its target. Required by
        // RunningAgent.awaitGatewayConnected to scan the agent's logs for connection markers.
        PrintStream originalStdout = System.out;
        ByteArrayOutputStream stdoutCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new TeeOutputStream(originalStdout, stdoutCapture), true, StandardCharsets.UTF_8));

        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(agentClassLoader);
        try {
            // Running Main's static initializer sets the JVM-wide system properties the agent
            // expects (java.awt.headless, netty / vertx DNS tuning, etc.). We then call
            // Launcher.start directly so a startup failure throws instead of exiting the JVM.
            Class.forName(MAIN_CLASS, true, agentClassLoader);
            Class<?> launcherCls = Class.forName(LAUNCHER_CLASS, true, agentClassLoader);
            Method startMethod = launcherCls.getDeclaredMethod("start", String[].class);
            startMethod.setAccessible(true);
            Object result = startMethod.invoke(null, (Object) effectiveArgs.toArray(new String[0]));
            if (!Boolean.TRUE.equals(result)) {
                throw new IllegalStateException("Agent failed to start — check logs");
            }
            return new RunningAgent(agentClassLoader, launcherCls, previousTccl, generatedConfig,
                    effectivePort, profileName, snapshotProfiles(), stdoutCapture, originalStdout);
        } catch (RuntimeException ex) {
            cleanupAfterFailure(agentClassLoader, previousTccl, generatedConfig, originalStdout);
            throw ex;
        } catch (ReflectiveOperationException ex) {
            cleanupAfterFailure(agentClassLoader, previousTccl, generatedConfig, originalStdout);
            Throwable cause = ex instanceof InvocationTargetException i ? i.getTargetException() : ex;
            throw new IllegalStateException("Reflective invocation of agent Launcher failed: " + cause, cause);
        }
    }

    /**
     * For every registered extension profile that names a {@code controllerClass} /
     * {@code controller}, try to load that class via the surrounding thread's context
     * classloader and, if successful, add its on-disk code source URL to the extension
     * classpath. This lets callers skip {@link #extensionCodeSource(Class...)} when the
     * controller class is already importable in the launching JVM.
     */
    private void addInferredExtensionSources() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (Map<String, Object> body : extensionProfiles.values()) {
            Object declared = body.get(CONTROLLER_CLASS_KEY);
            if (declared == null) {
                declared = body.get(CONTROLLER_KEY);
            }
            if (!(declared instanceof String fqn) || fqn.isBlank()) {
                continue;
            }
            try {
                Class<?> controllerClass = Class.forName(fqn, false, cl);
                URL source = codeSourceOf(controllerClass);
                if (!extensionUrls.contains(source)) {
                    extensionUrls.add(source);
                }
            } catch (ClassNotFoundException ignored) {
                // Not on the launching classpath — fine; the agent may still locate it from lib_ext/.
            }
        }
    }

    private void rejectConflictingArg(String option, String message) {
        for (String arg : arguments) {
            if (option.equals(arg) || arg.startsWith(option + "=")) {
                throw new IllegalStateException(message);
            }
        }
    }

    /**
     * Emits an INFO summary of exactly how the agent will execute: jars source (deployed vs
     * local-build fallback), conf source, base {@code config.yml}, {@code workato.properties}
     * override, gateway trust-store origin, run mode (local HTTP vs Workato gateway),
     * registered extension profiles, and user-supplied launcher args / system properties. Lets
     * the test author verify at a glance that the agent is wired the way they expect, without
     * having to parse the launcher CLI by hand.
     */
    private void logStartupSummary(List<String> effectiveArgs, int effectivePort) {
        boolean fallback = !distPath.equals(confPath);
        Path effectiveBaseConfig = baseConfigPath != null
                ? baseConfigPath
                : confPath.resolve("conf").resolve("config.yml");
        boolean baseConfigPresent = Files.isRegularFile(effectiveBaseConfig);
        Path looseWorkatoProperties = confPath.resolve("conf").resolve(WORKATO_PROPERTIES_RESOURCE);
        boolean workatoPropertiesOverridden = Files.isRegularFile(looseWorkatoProperties)
                && !containsOption(arguments, OPTION_WORKATO_PROPERTIES);
        String trustStore = describeTrustStore(effectiveArgs);
        String mode = effectivePort > 0
                ? "direct local HTTP at http://" + LISTEN_HOST + ":" + effectivePort
                  + " (connector mode — Workato gateway connection suppressed)"
                : "Workato gateway (outbound mTLS; agent appears online in the Workato cloud)";
        Map<String, Object> primaryProfile = extensionProfiles.get(profileName);
        Object controller = primaryProfile.getOrDefault(CONTROLLER_CLASS_KEY,
                primaryProfile.get(CONTROLLER_KEY));

        StringBuilder sb = new StringBuilder(512);
        sb.append("DeployedAgentLauncher starting agent — configuration summary:");
        sb.append("\n  profile             = '").append(profileName).append("'")
                .append(" (controller=").append(controller == null ? "<unset>" : controller)
                .append(", keys=").append(primaryProfile.size()).append(")");
        if (extensionProfiles.size() > 1) {
            List<String> others = new ArrayList<>(extensionProfiles.keySet());
            others.remove(profileName);
            sb.append("\n  foreign profiles    = ").append(others);
        }
        sb.append("\n  jars from           = ").append(distPath.resolve("lib"))
                .append(fallback ? "  (local-build fallback — no lib/ under " + confPath + ")"
                        : "  (deployed agent at path)");
        sb.append("\n  conf from           = ").append(confPath.resolve("conf"));
        sb.append("\n  base config.yml     = ").append(baseConfigPresent ? effectiveBaseConfig
                : "<none — starting from empty root>");
        sb.append("\n  generated config    = ").append(effectiveArgs.get(effectiveArgs.indexOf(OPTION_CONF) + 1));
        sb.append("\n  workato.properties  = ").append(workatoPropertiesOverridden
                ? looseWorkatoProperties + "  (loose override; wins over jar-bundled)"
                : "<jar-bundled default>");
        sb.append("\n  gateway trust-store = ").append(trustStore);
        sb.append("\n  run mode            = ").append(mode);
        if (!systemProperties.isEmpty()) {
            sb.append("\n  system properties   = ").append(systemProperties);
        }
        if (!arguments.isEmpty()) {
            sb.append("\n  user launcher args  = ").append(arguments);
        }

        log.info(sb.toString());
    }

    private String describeTrustStore(List<String> effectiveArgs) {
        int idx = effectiveArgs.indexOf(OPTION_CERT);
        if (idx < 0 || idx + 1 >= effectiveArgs.size()) {
            for (String arg : effectiveArgs) {
                if (arg.startsWith(OPTION_CERT + "=")) {
                    return arg.substring(OPTION_CERT.length() + 1) + "  (explicit --cert from argument(...))";
                }
            }
            return "<jar-bundled default>";
        }
        String value = effectiveArgs.get(idx + 1);
        boolean fromUserArgs = containsOption(arguments, OPTION_CERT);
        return value + (fromUserArgs
                ? "  (explicit --cert from argument(...))"
                : "  (loose-conf override)");
    }

    private Path writeMergedConfig(Path confRoot) {
        Path base = baseConfigPath != null ? baseConfigPath : confRoot.resolve("conf").resolve("config.yml");
        Map<String, Object> root = Files.isRegularFile(base) ? readYamlRoot(base) : new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        Map<String, Object> extensions = root.get(EXTENSIONS_SECTION) instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        extensions.putAll(extensionProfiles);
        root.put(EXTENSIONS_SECTION, extensions);

        try {
            Path tempFile = Files.createTempFile("agent-config-", ".yml");
            YAML.writeValue(tempFile.toFile(), root);
            return tempFile;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write merged agent config file", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Static helpers for tests that drive the runner.
    // ---------------------------------------------------------------------------------------

    private Path resolveDistPath() {
        if (!Files.isDirectory(distPath)) {
            throw new IllegalStateException("Agent distribution path is not a directory: " + distPath);
        }
        if (!Files.isDirectory(distPath.resolve("lib"))) {
            throw new IllegalStateException(
                    "Agent distribution path has no lib/ subdirectory: " + distPath);
        }
        return distPath;
    }

    private List<URL> buildClasspath(Path dist) {
        Set<URL> urls = new LinkedHashSet<>();
        collectJars(dist.resolve("lib"), urls);
        collectJars(dist.resolve("lib_ext"), urls);
        urls.addAll(extensionUrls);
        return new ArrayList<>(urls);
    }

    private Map<String, Map<String, Object>> snapshotProfiles() {
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : extensionProfiles.entrySet()) {
            snapshot.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Minimal {@link OutputStream} tee: every write is forwarded to both delegates. Used to
     * duplicate the agent's console output into a buffer scanned by
     * {@link RunningAgent#awaitGatewayConnected(Duration)}.
     */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream primary;
        private final OutputStream secondary;

        TeeOutputStream(OutputStream primary, OutputStream secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            primary.write(b);
            secondary.write(b);
        }

        @Override
        public synchronized void write(byte[] data, int off, int len) throws IOException {
            primary.write(data, off, len);
            secondary.write(data, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            primary.flush();
            secondary.flush();
        }

        @Override
        public void close() {
            // Do not close the primary (original System.out) — it is restored by the runner on
            // RunningAgent.stop(). The secondary buffer needs no closing either.
        }
    }

    /**
     * Handle to a running agent. Call {@link #stop()} (or use try-with-resources) to shut
     * the agent down and release the classloader.
     */
    public static final class RunningAgent implements AutoCloseable {

        private final URLClassLoader classLoader;
        private final Class<?> launcherClass;
        private final ClassLoader previousContextClassLoader;
        private final Path generatedConfig;
        private final int port;
        private final String profileName;
        private final Map<String, Map<String, Object>> profiles;
        private final ByteArrayOutputStream stdoutCapture;
        private final PrintStream originalStdout;
        private boolean stopped;

        private RunningAgent(URLClassLoader classLoader, Class<?> launcherClass,
                             ClassLoader previousContextClassLoader, Path generatedConfig,
                             int port, String profileName, Map<String, Map<String, Object>> profiles,
                             ByteArrayOutputStream stdoutCapture, PrintStream originalStdout) {
            this.classLoader = classLoader;
            this.launcherClass = launcherClass;
            this.previousContextClassLoader = previousContextClassLoader;
            this.generatedConfig = generatedConfig;
            this.port = port;
            this.profileName = profileName;
            this.profiles = profiles;
            this.stdoutCapture = stdoutCapture;
            this.originalStdout = originalStdout;
        }

        public ClassLoader classLoader() {
            return classLoader;
        }

        /**
         * The TCP port the agent's connector listener is bound to on {@code 127.0.0.1}.
         * Throws {@link IllegalStateException} if the agent is running in full gateway mode
         * (neither {@link DeployedAgentLauncher#openLocalAccess(int)} nor
         * {@link DeployedAgentLauncher#openLocalAccess()} was called before {@code start()}).
         */
        public int port() {
            requireLocalAccessOpened();
            return port;
        }

        private void requireLocalAccessOpened() {
            if (port <= 0) {
                throw new IllegalStateException(
                        "Direct local HTTP access was not opened — no connector listener is bound. "
                                + "Call DeployedAgentLauncher.openLocalAccess(int) or "
                                + "DeployedAgentLauncher.openLocalAccess() before start() to open it "
                                + "(incompatible with connecting to the Workato gateway).");
            }
        }

        /**
         * The name of this runner's extension profile — the one passed to
         * {@link DeployedAgentLauncher#create(String, Path)}.
         */
        public String profileName() {
            return profileName;
        }

        /**
         * The final body of this runner's extension profile — the merge of everything
         * configured via {@link DeployedAgentLauncher#controllerClass(Class)},
         * {@link DeployedAgentLauncher#property(String, Object)}, {@link DeployedAgentLauncher#properties(Map)}
         * and {@link DeployedAgentLauncher#yaml(String)}, in call order. This is the exact map the
         * runner wrote under {@code extensions.{profileName}} in the generated
         * {@code config.yml}.
         */
        public Map<String, Object> profile() {
            return profiles.get(profileName);
        }

        /**
         * The final body of an arbitrary registered extension profile (including foreign ones
         * tweaked via {@link DeployedAgentLauncher#extensionProperty(String, String, Object)}). Throws
         * {@link IllegalArgumentException} if the profile was not registered.
         */
        public Map<String, Object> extensionProfile(String name) {
            Map<String, Object> body = profiles.get(name);
            if (body == null) {
                throw new IllegalArgumentException("No extension profile registered under name '" + name + "'");
            }
            return body;
        }

        /**
         * Snapshot of every registered extension profile keyed by profile name. The returned
         * map and its values are unmodifiable.
         */
        public Map<String, Map<String, Object>> extensionProfiles() {
            return profiles;
        }

        /**
         * Blocks until the agent writes a {@code "Connected to gateway"} log line to standard
         * output, or {@code timeout} elapses. Returns the full matching line (including the
         * gateway host:port, which is useful for assertions). The runner tees {@code System.out}
         * at {@link #start()} time, so this method scans the captured buffer, not an external
         * log file.
         *
         * <p>Expected to be used in gateway (production) mode — i.e., without calling
         * {@link DeployedAgentLauncher#openLocalAccess(int)} or
         * {@link DeployedAgentLauncher#openLocalAccess()}. Direct local HTTP access and the
         * Workato gateway connection are mutually exclusive, so this call will time out
         * whenever local access was opened.
         *
         * @throws java.util.concurrent.TimeoutException when the marker does not appear within
         *                                               the budget.
         */
        public String awaitGatewayConnected(Duration timeout)
                throws java.util.concurrent.TimeoutException, InterruptedException {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (true) {
                String captured = stdoutCapture.toString(StandardCharsets.UTF_8);
                int idx = captured.indexOf(GATEWAY_CONNECTED_MARKER);
                if (idx >= 0) {
                    int lineStart = captured.lastIndexOf('\n', idx) + 1;
                    int lineEnd = captured.indexOf('\n', idx);
                    if (lineEnd < 0) {
                        lineEnd = captured.length();
                    }
                    return captured.substring(lineStart, lineEnd);
                }
                if (System.nanoTime() >= deadlineNanos) {
                    throw new java.util.concurrent.TimeoutException(
                            "Agent did not log '" + GATEWAY_CONNECTED_MARKER + "' within " + timeout
                                    + ". Verify the agent is in gateway mode (no openLocalAccess(...)) "
                                    + "and the dist's activation certificate is valid.");
                }
                Thread.sleep(250);
            }
        }

        /**
         * Issues an HTTP GET against the extension endpoint represented by the given controller
         * method reference. The profile mount point ({@code /ext/{profileName}/}) and the method's
         * route (from {@code @GetMapping}) together form the full URL; the host / port are the
         * ones the agent is bound to. Throws {@link IllegalStateException} when the referenced
         * method is not annotated with {@code @GetMapping}, or when this runner's profile does
         * not declare the controller class.
         */
        public <C, R> HttpResponse<String> httpGet(AgentExtensionHarness.Ref0<C, R> methodRef) {
            return httpSend(buildControllerRequest(methodRef, new Object[0], HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref0)} for controller methods that declare one
         * parameter total — a {@code @PathVariable}, {@code @RequestHeader},
         * {@code HttpServletResponse}, etc. The resolver never calls through the method
         * reference, so the actual argument type is immaterial; only the SAM arity has to match
         * so Java accepts the method-reference literal. Pass one value in {@code pathVars} per
         * declared {@code @PathVariable} parameter — non-path parameters are skipped.
         */
        public <C, A, R> HttpResponse<String> httpGet(AgentExtensionHarness.Ref1<C, A, R> methodRef,
                                                      Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for two-arg controller methods.
         */
        public <C, A1, A2, R> HttpResponse<String> httpGet(AgentExtensionHarness.Ref2<C, A1, A2, R> methodRef,
                                                           Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for three-arg controller methods.
         */
        public <C, A1, A2, A3, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref3<C, A1, A2, A3, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for four-arg controller methods.
         */
        public <C, A1, A2, A3, A4, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref4<C, A1, A2, A3, A4, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for five-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref5<C, A1, A2, A3, A4, A5, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for six-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref6<C, A1, A2, A3, A4, A5, A6, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for seven-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref7<C, A1, A2, A3, A4, A5, A6, A7, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for eight-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref8<C, A1, A2, A3, A4, A5, A6, A7, A8, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for nine-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref9<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for ten-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> HttpResponse<String> httpGet(
                AgentExtensionHarness.Ref10<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> methodRef, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.GET, null).build());
        }

        /**
         * Issues an HTTP POST with a JSON body (serialized via Jackson) against the extension
         * endpoint represented by the given controller method reference. See
         * {@link #httpGet(AgentExtensionHarness.Ref0)} for route construction. {@code body} may
         * be {@code null} for an empty POST.
         */
        public <C, R> HttpResponse<String> httpPost(AgentExtensionHarness.Ref0<C, R> methodRef, Object body) {
            return httpSend(buildControllerRequest(methodRef, new Object[0], HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref0, Object)} for controller methods that
         * declare one parameter total (typically a single {@code @RequestBody}). See
         * {@link #httpGet(AgentExtensionHarness.Ref1, Object...)} for the arity-vs-value-vs-pathVars
         * split.
         */
        public <C, A, R> HttpResponse<String> httpPost(AgentExtensionHarness.Ref1<C, A, R> methodRef,
                                                       Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for controller methods
         * that declare two parameters total (e.g., {@code @RequestBody} +
         * {@code @RequestHeader}).
         */
        public <C, A1, A2, R> HttpResponse<String> httpPost(AgentExtensionHarness.Ref2<C, A1, A2, R> methodRef,
                                                            Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for controller methods
         * that declare three parameters total.
         */
        public <C, A1, A2, A3, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref3<C, A1, A2, A3, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for four-arg controller methods.
         */
        public <C, A1, A2, A3, A4, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref4<C, A1, A2, A3, A4, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for five-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref5<C, A1, A2, A3, A4, A5, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for six-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref6<C, A1, A2, A3, A4, A5, A6, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for seven-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref7<C, A1, A2, A3, A4, A5, A6, A7, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for eight-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref8<C, A1, A2, A3, A4, A5, A6, A7, A8, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for nine-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref9<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * {@link #httpPost(AgentExtensionHarness.Ref1, Object, Object...)} for ten-arg controller methods.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> HttpResponse<String> httpPost(
                AgentExtensionHarness.Ref10<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> methodRef, Object body, Object... pathVars) {
            return httpSend(buildControllerRequest(methodRef, pathVars, HttpMethod.POST, body).build());
        }

        /**
         * Returns the full URL this runner would issue requests to for the given zero-arg
         * controller method reference — {@code http://127.0.0.1:{port}/ext/{profileName}} +
         * the method's annotation-declared route. Useful when a test wants to assemble its own
         * {@link HttpRequest} with the standard {@link HttpClient} but still have the URL path
         * come from the controller annotation rather than a duplicated string.
         */
        public <C, R> String urlFor(AgentExtensionHarness.Ref0<C, R> methodRef) {
            return controllerUrl(methodRef, new Object[0]);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for one-arg controller method references,
         * substituting {@code @PathVariable} placeholders with {@code pathVars}.
         */
        public <C, A, R> String urlFor(AgentExtensionHarness.Ref1<C, A, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for two-arg controller method references.
         */
        public <C, A1, A2, R> String urlFor(AgentExtensionHarness.Ref2<C, A1, A2, R> methodRef,
                                            Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for three-arg controller method references.
         */
        public <C, A1, A2, A3, R> String urlFor(AgentExtensionHarness.Ref3<C, A1, A2, A3, R> methodRef,
                                                Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for four-arg controller method references.
         */
        public <C, A1, A2, A3, A4, R> String urlFor(
                AgentExtensionHarness.Ref4<C, A1, A2, A3, A4, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for five-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, R> String urlFor(
                AgentExtensionHarness.Ref5<C, A1, A2, A3, A4, A5, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for six-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, R> String urlFor(
                AgentExtensionHarness.Ref6<C, A1, A2, A3, A4, A5, A6, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for seven-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, R> String urlFor(
                AgentExtensionHarness.Ref7<C, A1, A2, A3, A4, A5, A6, A7, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for eight-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, R> String urlFor(
                AgentExtensionHarness.Ref8<C, A1, A2, A3, A4, A5, A6, A7, A8, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for nine-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> String urlFor(
                AgentExtensionHarness.Ref9<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * {@link #urlFor(AgentExtensionHarness.Ref0)} for ten-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> String urlFor(
                AgentExtensionHarness.Ref10<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> methodRef, Object... pathVars) {
            return controllerUrl(methodRef, pathVars);
        }

        /**
         * Returns an {@link HttpRequest.Builder} pre-filled with the URL derived from the
         * controller method reference and the HTTP method matching the controller annotation
         * (GET → {@code .GET()}, DELETE → {@code .DELETE()}, anything else via
         * {@code .method(name, noBody())}). The builder also carries the same defaults the
         * convenience methods use — {@code Accept: application/json} and a 10-second request
         * timeout — which the caller can override.
         *
         * <p>Use this when the convenience overloads ({@link #httpGet}, {@link #httpPost}) are
         * not expressive enough: custom headers, non-JSON bodies, overridden timeouts, mTLS,
         * {@code Expect: 100-continue}, etc. Finish the builder with {@code .build()} and send
         * it through {@link #send(HttpRequest)}.
         *
         * <pre>{@code
         * HttpRequest req = agent.requestFor(NtlmExtension::request)
         *         .header("Authorization", "NTLM …")
         *         .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
         *         .build();
         * HttpResponse<String> res = agent.send(req);
         * }</pre>
         */
        public <C, R> HttpRequest.Builder requestFor(AgentExtensionHarness.Ref0<C, R> methodRef) {
            return controllerRequestBuilder(methodRef, new Object[0]);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for one-arg controller method references.
         */
        public <C, A, R> HttpRequest.Builder requestFor(AgentExtensionHarness.Ref1<C, A, R> methodRef,
                                                        Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for two-arg controller method references.
         */
        public <C, A1, A2, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref2<C, A1, A2, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for three-arg controller method references.
         */
        public <C, A1, A2, A3, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref3<C, A1, A2, A3, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for four-arg controller method references.
         */
        public <C, A1, A2, A3, A4, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref4<C, A1, A2, A3, A4, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for five-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref5<C, A1, A2, A3, A4, A5, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for six-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref6<C, A1, A2, A3, A4, A5, A6, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for seven-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref7<C, A1, A2, A3, A4, A5, A6, A7, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for eight-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref8<C, A1, A2, A3, A4, A5, A6, A7, A8, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for nine-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref9<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for ten-arg controller method references.
         */
        public <C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> HttpRequest.Builder requestFor(
                AgentExtensionHarness.Ref10<C, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, R> methodRef, Object... pathVars) {
            return controllerRequestBuilder(methodRef, pathVars);
        }

        /**
         * Sends the given {@link HttpRequest} through the same {@link HttpClient} the
         * convenience methods use (5-second connect timeout, string body handler). Pairs with
         * {@link #requestFor(AgentExtensionHarness.Ref0)} for tests that assemble a custom
         * request through the builder.
         */
        public HttpResponse<String> send(HttpRequest request) {
            return httpSend(request);
        }

        private HttpRequest.Builder buildControllerRequest(java.io.Serializable methodRef, Object[] pathVars,
                                                           HttpMethod expected, Object body) {
            requireLocalAccessOpened();
            ControllerMethodResolver.Resolved resolved = ControllerMethodResolver.resolve(methodRef, pathVars);
            if (!resolved.httpMethod().equals(expected)) {
                throw new IllegalStateException(
                        "Method " + resolved.controllerClass().getName() + " is annotated as "
                                + resolved.httpMethod() + " but " + expected + " was invoked.");
            }
            assertControllerBelongsToProfile(resolved.controllerClass());
            String url = "http://" + LISTEN_HOST + ":" + port + "/ext/" + profileName + resolved.controllerPath();
            HttpRequest.Builder req = baseRequest(url);
            if (expected == HttpMethod.GET) {
                return req.GET();
            }
            if (expected == HttpMethod.POST) {
                HttpRequest.BodyPublisher publisher;
                if (body == null) {
                    publisher = HttpRequest.BodyPublishers.noBody();
                }
                else {
                    try {
                        publisher = HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
                    } catch (JacksonException e) {
                        throw new IllegalStateException("Failed to serialize POST body to JSON", e);
                    }
                    req.header("Content-Type", "application/json");
                }
                return req.POST(publisher);
            }
            throw new IllegalStateException("Unsupported HTTP method: " + expected);
        }

        private String controllerUrl(java.io.Serializable methodRef, Object[] pathVars) {
            requireLocalAccessOpened();
            ControllerMethodResolver.Resolved resolved = ControllerMethodResolver.resolve(methodRef, pathVars);
            assertControllerBelongsToProfile(resolved.controllerClass());
            return "http://" + LISTEN_HOST + ":" + port + "/ext/" + profileName + resolved.controllerPath();
        }

        private HttpRequest.Builder controllerRequestBuilder(java.io.Serializable methodRef, Object[] pathVars) {
            requireLocalAccessOpened();
            ControllerMethodResolver.Resolved resolved = ControllerMethodResolver.resolve(methodRef, pathVars);
            assertControllerBelongsToProfile(resolved.controllerClass());
            String url = "http://" + LISTEN_HOST + ":" + port + "/ext/" + profileName + resolved.controllerPath();
            HttpRequest.Builder req = baseRequest(url);
            HttpMethod method = resolved.httpMethod();
            if (method == HttpMethod.GET) {
                req.GET();
            }
            else if (method == HttpMethod.DELETE) {
                req.DELETE();
            }
            else {
                req.method(method.name(), HttpRequest.BodyPublishers.noBody());
            }
            return req;
        }

        private void assertControllerBelongsToProfile(Class<?> controllerClass) {
            Map<String, Object> body = profiles.get(profileName);
            Object declared = body.get(CONTROLLER_CLASS_KEY);
            if (declared == null) {
                declared = body.get(CONTROLLER_KEY);
            }
            if (!(declared instanceof String declaredFqn)) {
                throw new IllegalStateException("Profile '" + profileName
                        + "' has no controllerClass set — call controllerClass(" + controllerClass.getSimpleName()
                        + ".class) or yaml(...) before start().");
            }
            if (!declaredFqn.equals(controllerClass.getName())) {
                throw new IllegalStateException("Profile '" + profileName + "' declares controller "
                        + declaredFqn + " but the method reference targets " + controllerClass.getName() + ".");
            }
        }

        public synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            try {
                Method shutdown = launcherClass.getDeclaredMethod("shutdown");
                shutdown.setAccessible(true);
                shutdown.invoke(null);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e instanceof InvocationTargetException i ? i.getTargetException() : e;
                throw new IllegalStateException("Reflective invocation of Launcher.shutdown failed: " + cause, cause);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
                System.setOut(originalStdout);
                try {
                    classLoader.close();
                } catch (IOException ignored) {
                }
                deleteQuietly(generatedConfig);
            }
        }

        @Override
        public void close() {
            stop();
        }
    }
}
