/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.EchoController;
import com.workato.agent.extension.test.testext.PortableEchoController;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for {@link DeployedAgentLauncher}: launches the real agent
 * in-process against every supported dist layout, in each of the two mutually exclusive run
 * modes.
 *
 * <h2>Not for CI</h2>
 * <p>
 * These tests require three deployed and activated agent distributions on disk (real
 * {@code cert.pem} / {@code cert.key} signed by Workato for a real workspace, matching
 * gateway environment, …) — per-developer-machine state that cannot be reproduced in CI.
 * Keep the class {@code @Disabled} by default; run individual methods manually from the IDE
 * when verifying end-to-end behaviour against the real agent or reproducing a mysterious
 * issue that only surfaces inside a full agent process.
 *
 * <h2>Required local setup</h2>
 * <p>
 * Three on-disk directories must exist, each pre-<strong>activated</strong> against the
 * Workato cloud (i.e., with {@code conf/cert.key} + {@code conf/cert.pem} present and signed
 * by Workato for your workspace — otherwise the gateway-mode tests fail with
 * {@code "Agent SSL context is incomplete"} and local-HTTP tests fail during startup for the
 * same reason). All three dists should be activated against the <strong>same</strong>
 * workspace so each represents the same agent identity from Workato's point of view. Edit
 * the path constants below to match your machine.
 *
 * <p>Setup checklist, start to finish:
 * <ol>
 *     <li>Download the OPA 34 agent dist and extract it to {@link #DEPLOYED_WITH_CONF}. Run
 *         {@code ./bin/activate.sh ...} (or copy an existing {@code cert.key}/{@code cert.pem}
 *         pair into {@code conf/}). Then drop a loose
 *         {@code conf/workato.properties} + the {@code root_ca_*.pem} files it names next to
 *         the cert, so the launcher injects {@code --workato-properties} / {@code --cert}
 *         and overrides the jar-bundled defaults.</li>
 *     <li>Copy the activated {@code conf/} tree from step 1 into {@link #CONF_ONLY} —
 *         <strong>only</strong> {@code conf/}, no {@code lib/}. Run
 *         {@code ./gradlew :agent-launcher:installDist} once from the repo root so the
 *         launcher can locate the locally-built agent under
 *         {@code modules/agent-launcher/build/install/*} and use it for jars.</li>
 *     <li>Download the OPA <strong>33</strong> agent dist and extract it to
 *         {@link #DEPLOYED_WITHOUT_CONF}. Activate it (cert files only — leave {@code conf/}
 *         otherwise empty: <strong>no loose {@code workato.properties}</strong>). The jars'
 *         bundled {@code agent.default.gateway} and {@code gateway.ca.file} must point at the
 *         same gateway environment the cert was issued for, because without a loose
 *         {@code workato.properties} the launcher has nothing to override them with.</li>
 *     <li>Un-comment the {@code @Disabled} annotation only when you want to skip the suite
 *         again; by default (as checked in) it runs.</li>
 * </ol>
 *
 * <p>Final layouts for reference:
 *
 * <ul>
 *     <li>{@link #DEPLOYED_WITH_CONF} — fully deployed OPA agent, with a loose
 *         {@code conf/workato.properties} + {@code conf/root_ca_*.pem} overriding the
 *         jar-bundled defaults:
 *         <pre>
 * /Users/krocodl/Apps/OPA-34-test/
 *     lib/
 *         agent-launcher-*.jar, agent-core-*.jar, extension-api-*.jar, …
 *     conf/
 *         cert.key, cert.pem                  # REQUIRED — activation output
 *         config.yml                          (optional)
 *         workato.properties                  # loose override — wins over the jar-bundled copy
 *         root_ca_*.pem                       # PEM files listed in gateway.ca.file
 *         </pre>
 *     </li>
 *     <li>{@link #CONF_ONLY} — only {@code conf/}, <strong>no</strong> {@code lib/}
 *         directory. The launcher auto-detects the missing {@code lib/} and transparently
 *         falls back to the locally-built agent (prerequisite: run
 *         {@code ./gradlew :agent-launcher:installDist} once beforehand). A loose
 *         {@code workato.properties} is <strong>required</strong> here because the local
 *         build's jar-bundled defaults may target a different environment than the external
 *         cert:
 *         <pre>
 * /Users/krocodl/Apps/OPA-34-test-conf-only/
 *     conf/
 *         cert.key, cert.pem                  # REQUIRED — activation output
 *         config.yml                          (optional)
 *         workato.properties                  # REQUIRED — supplies gateway.ca.file for trust-store rewiring
 *         root_ca_*.pem                       # REQUIRED — PEM files listed in gateway.ca.file
 *         </pre>
 *     </li>
 *     <li>{@link #DEPLOYED_WITHOUT_CONF} — fully deployed OPA <strong>33</strong> agent,
 *         <strong>without</strong> any loose {@code workato.properties} / {@code root_ca_*.pem}
 *         overrides. The agent relies entirely on the jar-bundled defaults for
 *         {@code agent.default.gateway} / {@code gateway.ca.file} / {@code agent.version} / …,
 *         so the dist's own gateway environment <strong>must</strong> match the one the cert
 *         was issued for:
 *         <pre>
 * /Users/krocodl/Apps/OPA-33-test-without-conf/
 *     lib/
 *         agent-launcher-*.jar, agent-core-*.jar, …     (no extension-api-*.jar on OPA 33)
 *     conf/
 *         cert.key, cert.pem                  # REQUIRED — activation output, no overrides sit next to them
 *         config.yml                          (optional)
 *         </pre>
 *     </li>
 * </ul>
 *
 * <h2>Controller choice: OPA 33 vs OPA 34</h2>
 * <p>
 * The {@link #DEPLOYED_WITH_CONF} and {@link #CONF_ONLY} layouts target OPA 34, which ships
 * {@code extension-api-*.jar} in {@code lib/} — the corresponding tests mount
 * {@link EchoController} which {@code @Autowires} {@code AgentExtensionV1}. The
 * {@link #DEPLOYED_WITHOUT_CONF} layout targets OPA 33 where the V1 API jar is absent, so its
 * tests mount {@link PortableEchoController} instead (depends only on Spring, no agent-side
 * types) so the controller still class-loads inside the agent's isolated classloader.
 *
 * <h2>Test matrix</h2>
 * <p>
 * Six tests cover every (layout, mode) combination; each test name names both axes:
 *
 * <table>
 *     <caption>Layout × mode coverage</caption>
 *     <tr><th>Dist layout</th>                 <th>Direct local HTTP</th>                                                        <th>Workato gateway</th></tr>
 *     <tr><td>deployed, with loose conf</td>   <td>{@link #deployedAgentWithLooseConfig_opensLocalHttpAccess}</td>               <td>{@link #deployedAgentWithLooseConfig_connectsToWorkatoGateway}</td></tr>
 *     <tr><td>loose conf only (no lib)</td>    <td>{@link #looseConfigOnly_opensLocalHttpAccessViaLocalBuildFallback}</td>       <td>{@link #looseConfigOnly_connectsToWorkatoGatewayViaLocalBuildFallback}</td></tr>
 *     <tr><td>deployed, no loose conf</td>     <td>{@link #deployedAgentWithoutLooseConfig_opensLocalHttpAccess}</td>            <td>{@link #deployedAgentWithoutLooseConfig_connectsToWorkatoGateway}</td></tr>
 * </table>
 * <p>
 * {@link #exercisesEveryBuilderMethodAndRunningAgentAccessor()} re-runs the fully-deployed /
 * local-HTTP combination but chains every builder method and queries every
 * {@code RunningAgent} accessor in one go, pinning the full public surface.
 *
 * <p><strong>Isolation:</strong> each {@code @Test} method launches the real agent, which
 * mutates process-global state ({@code Launcher}'s state machine does not permit a second
 * start in the same JVM). Run <strong>one method at a time</strong>.
 */
@Disabled("Requires real OPA installations under /Users/krocodl/Apps/OPA-34-test*")
class DeployedAgentLauncherIntegrationTest {

    private static final String PROFILE = "my-profile";
    private static final Path DEPLOYED_WITH_CONF = Paths.get("/Users/krocodl/Apps/OPA-34-test");
    private static final Path CONF_ONLY = Paths.get("/Users/krocodl/Apps/OPA-34-test-conf-only");
    private static final Path DEPLOYED_WITHOUT_CONF = Paths.get("/Users/krocodl/Apps/OPA-33-test-without-conf");
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------------------------------------------------------------------------------------
    // Layout 1: fully deployed agent WITH a loose workato.properties override.
    // ---------------------------------------------------------------------------------------

    private static void assertEchoRespondsOverLocalHttp(DeployedAgentLauncher.RunningAgent agent,
                                                        String expectedGreeting) throws Exception {
        assertThat(agent.port()).isPositive();
        assertThat(agent.profileName()).isEqualTo(PROFILE);

        HttpResponse<String> res = agent.httpGet(EchoController::echo);
        assertThat(res.statusCode()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(res.body(), Map.class);
        assertThat(body)
                .containsEntry("greeting", expectedGreeting)
                .containsEntry("profile", PROFILE);
    }

    // PortableEchoController depends only on Spring (no AgentExtensionV1), so its /echo
    // payload does not include the profile — assert only the greeting round-trip.
    private static void assertPortableEchoRespondsOverLocalHttp(DeployedAgentLauncher.RunningAgent agent,
                                                                String expectedGreeting) throws Exception {
        assertThat(agent.port()).isPositive();
        assertThat(agent.profileName()).isEqualTo(PROFILE);

        HttpResponse<String> res = agent.httpGet(PortableEchoController::echo);
        assertThat(res.statusCode()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(res.body(), Map.class);
        assertThat(body).containsEntry("greeting", expectedGreeting);
    }

    // ---------------------------------------------------------------------------------------
    // Layout 2: loose conf only (no lib/) — triggers the local-build fallback.
    // ---------------------------------------------------------------------------------------

    private static void assertAgentConnectsToWorkatoGateway(DeployedAgentLauncher.RunningAgent agent)
            throws Exception {
        String connectLine = agent.awaitGatewayConnected(Duration.ofSeconds(60));
        assertThat(connectLine)
                .as("Agent must log that it has connected to the Workato gateway")
                .contains("Connected to gateway at");
    }

    @Test
    void deployedAgentWithLooseConfig_opensLocalHttpAccess() throws Exception {
        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(PROFILE, DEPLOYED_WITH_CONF)
                .controllerClass(EchoController.class)
                .property("greeting", "hello")
                .openLocalAccess()
                .start()) {

            assertEchoRespondsOverLocalHttp(agent, "hello");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Layout 3: fully deployed agent WITHOUT a loose workato.properties override.
    // ---------------------------------------------------------------------------------------

    @Test
    void deployedAgentWithLooseConfig_connectsToWorkatoGateway() throws Exception {
        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(PROFILE, DEPLOYED_WITH_CONF)
                .controllerClass(EchoController.class)
                .property("greeting", "hello")
                // No .openLocalAccess() — stay in full gateway mode.
                .start()) {

            assertAgentConnectsToWorkatoGateway(agent);
        }
    }

    @Test
    void looseConfigOnly_opensLocalHttpAccessViaLocalBuildFallback() throws Exception {
        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(PROFILE, CONF_ONLY)
                .controllerClass(EchoController.class)
                .property("greeting", "hello")
                .openLocalAccess()
                .start()) {

            assertEchoRespondsOverLocalHttp(agent, "hello");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Kitchen-sink: chain every builder method, assert every RunningAgent accessor.
    // ---------------------------------------------------------------------------------------

    @Test
    void looseConfigOnly_connectsToWorkatoGatewayViaLocalBuildFallback() throws Exception {
        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(PROFILE, CONF_ONLY)
                .controllerClass(EchoController.class)
                .property("greeting", "hello")
                // No .openLocalAccess() — stay in full gateway mode.
                .start()) {

            assertAgentConnectsToWorkatoGateway(agent);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared assertions.
    // ---------------------------------------------------------------------------------------

    @Test
    void deployedAgentWithoutLooseConfig_opensLocalHttpAccess() throws Exception {
        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(PROFILE, DEPLOYED_WITHOUT_CONF)
                .controllerClass(PortableEchoController.class)
                .property("greeting", "hello")
                .openLocalAccess()
                .start()) {

            assertPortableEchoRespondsOverLocalHttp(agent, "hello");
        }
    }

    @Test
    void deployedAgentWithoutLooseConfig_connectsToWorkatoGateway() throws Exception {
        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(PROFILE, DEPLOYED_WITHOUT_CONF)
                .controllerClass(PortableEchoController.class)
                .property("greeting", "hello")
                // No .openLocalAccess() — stay in full gateway mode.
                .start()) {

            assertAgentConnectsToWorkatoGateway(agent);
        }
    }

    /**
     * Pins the full public surface of {@link DeployedAgentLauncher} and
     * {@link DeployedAgentLauncher.RunningAgent} in a single call chain against the fully
     * deployed agent in direct-local-HTTP mode. Exercises:
     *
     * <ul>
     *     <li>{@link DeployedAgentLauncher#yaml(String)} — baseline from
     *         {@code extensions/portable-echo.yml}, including a {@code yaml-only-key} to prove
     *         the YAML survived the merge</li>
     *     <li>{@link DeployedAgentLauncher#controllerClass(Class)} — explicit override that
     *         wins over the controller declared in the YAML</li>
     *     <li>{@link DeployedAgentLauncher#property(String, Object)} — single-key override
     *         that wins over the YAML's value</li>
     *     <li>{@link DeployedAgentLauncher#properties(Map)} — bulk merge</li>
     *     <li>{@link DeployedAgentLauncher#extensionCodeSource(Class[])} — explicit code
     *         source URL (deduplicates with the one inferred from controllerClass)</li>
     *     <li>{@link DeployedAgentLauncher#extensionProperty(String, String, Object)} —
     *         single-key edit of a foreign profile not present in the base config</li>
     *     <li>{@link DeployedAgentLauncher#extensionProperties(String, Map)} — bulk edit of
     *         the same foreign profile</li>
     *     <li>{@link DeployedAgentLauncher#argument(String...)} — raw launcher CLI flag</li>
     *     <li>{@link DeployedAgentLauncher#systemProperty(String, String)} — JVM-wide system
     *         property</li>
     *     <li>{@link DeployedAgentLauncher#openLocalAccess()} — opens direct local HTTP on an
     *         auto-allocated port</li>
     * </ul>
     * <p>
     * After {@code start()} every {@link DeployedAgentLauncher.RunningAgent} accessor
     * ({@code port}, {@code profileName}, {@code profile}, {@code extensionProfile(name)},
     * {@code extensionProfiles}) is queried, then an HTTP {@code GET} is round-tripped via
     * {@link DeployedAgentLauncher.RunningAgent#httpGet} to prove the merged configuration
     * actually reaches the controller.
     */
    @Test
    void exercisesEveryBuilderMethodAndRunningAgentAccessor() throws Exception {
        String foreignProfile = "other-profile";

        try (DeployedAgentLauncher.RunningAgent agent = DeployedAgentLauncher.create(PROFILE, DEPLOYED_WITH_CONF)
                .yaml("extensions/portable-echo.yml")                      // baseline from YAML fixture
                .controllerClass(EchoController.class)                     // override YAML's controllerClass
                .property("greeting", "override-greeting")                 // single-key override of YAML's greeting
                .properties(Map.of("bulk-one", "bulk-value-one",
                        "bulk-two", 42))                                   // bulk merge
                .extensionCodeSource(EchoController.class)                 // explicit code source (dedup with inferred)
                .extensionProperty(foreignProfile, "controllerClass",
                        EchoController.class.getName())                    // foreign-profile controller
                .extensionProperty(foreignProfile, "foreign-key", "foreign-value")
                .extensionProperties(foreignProfile,
                        Map.of("foreign-bulk", "foreign-bulk-value"))      // foreign-profile bulk
                .argument("--log-stdout")                                  // extra launcher arg
                .systemProperty("my.integration.marker", "set-by-test")    // JVM-wide system property
                .openLocalAccess()                                         // open direct local HTTP (disables gateway)
                .start()) {

            // Primary-profile accessors.
            assertThat(agent.port()).isPositive();
            assertThat(agent.profileName()).isEqualTo(PROFILE);
            assertThat(agent.profile())
                    .as("YAML baseline + per-key overrides + bulk merge must all be visible")
                    .containsEntry("controllerClass", EchoController.class.getName())  // explicit override wins
                    .containsEntry("greeting", "override-greeting")
                    .containsEntry("yaml-only-key", "yaml-only-value")
                    .containsEntry("bulk-one", "bulk-value-one")
                    .containsEntry("bulk-two", 42);

            // Foreign-profile accessors.
            assertThat(agent.extensionProfile(foreignProfile))
                    .containsEntry("foreign-key", "foreign-value")
                    .containsEntry("foreign-bulk", "foreign-bulk-value");

            assertThat(agent.extensionProfiles()).containsOnlyKeys(PROFILE, foreignProfile);

            // JVM-wide system property reached the current process.
            assertThat(System.getProperty("my.integration.marker")).isEqualTo("set-by-test");

            // HTTP smoke — EchoController returns greeting + profile.
            HttpResponse<String> res = agent.httpGet(EchoController::echo);
            assertThat(res.statusCode()).isEqualTo(200);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(res.body(), Map.class);
            assertThat(body)
                    .containsEntry("greeting", "override-greeting")
                    .containsEntry("profile", PROFILE);
        }
    }
}
