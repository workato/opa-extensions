# CLAUDE.md — workato-agent-extension-demo

Guidance for Claude agents working inside this module. Workspace-level
orientation (which modules exist, root toolchain, etc.) lives at the repo
root in `../CLAUDE.md`. Customer-facing documentation for this module's
sample extensions is in `README.md` next to this file.

## What this module is

Three sample extensions (`security`, `ntlm`, `timer`) plus the Workato
custom connectors that talk to them. Exists for two reasons:

1. **End-to-end exerciser.** Every pattern the API and the harness expose
   gets a worked example here, so regressions in either sibling module show
   up as broken demo tests on the next build.
2. **Reference for customer teams.** A new extension author should be able
   to clone one of the three subdirectories under
   `src/main/java/com/mycompany/onprem/<name>/`, follow the six-step
   development workflow in `README.md`, and ship.

This module is **not** published to Nexus — only `workato-agent-extension-api`
and `workato-agent-extension-test` are (future work).

## Authoritative docs — read before coding

Before writing or modifying anything under this module:

1. Read [`../workato-agent-extension-api/README.md`](../workato-agent-extension-api/README.md)
   end-to-end when touching production code under `src/main/java/`.
2. Read [`../workato-agent-extension-test/README.md`](../workato-agent-extension-test/README.md)
   end-to-end when touching or adding tests under `src/test/java/`.
3. Follow the patterns and constraints documented there. Do not invent
   alternative API usage, do not mock the Extension API by hand — use the
   facilities provided by the `extension-test` module.

## Dependency scoping — do not revert

The `jar` task produces a **minimal fat jar**: only libraries actually used
by extension code are shaded in. Anything the agent provides at runtime
(`workato-agent-extension-api`, Spring, Jackson, Jakarta, SLF4J) is declared
`compileOnly` and stays out of
`build/libs/workato-agent-extension-demo-<version>.jar`. Shading
agent-provided types would cause classloader conflicts in the agent — see
[`../workato-agent-extension-api/README.md`](../workato-agent-extension-api/README.md)
→ "do not shade it into your JAR".

Scoping rules — follow them when adding a new dependency:

- **Shaded (`implementation`)** — any new third-party library that the agent
  does NOT already provide. Today: `commons-codec`, `commons-lang3`. Add a
  trailing comment pointing at the extension that uses the library.
- **Provided by the agent (`compileOnly`)** — anything from Spring, Jackson,
  Jakarta, SLF4J, Apache HttpClient, or further `com.workato.*` packages.
  Compile against it, do not shade. `project(':workato-agent-extension-api')`
  is declared `compileOnly` for the same reason: the agent provides the API
  module at runtime.
- **Test-only (`testImplementation`)** — anything only the test harness
  needs. `project(':workato-agent-extension-test')` lives here.

The `configurations { testImplementation.extendsFrom compileOnly }` block at
the top of `dependencies { }` forwards every `compileOnly` entry onto the
test classpath — Gradle does NOT do this automatically, and without this
line `AgentExtensionHarness` loses Spring at test runtime.

Verify after any dependency change: `./gradlew clean :workato-agent-extension-demo:jar
&& unzip -l build/libs/workato-agent-extension-demo-*.jar | grep -c
org/springframework` must print `0`. If a Spring/Jackson/SLF4J class is in
the jar you shaded something you shouldn't have.

### Versions come from the BOM — do NOT inline them

Every GAV you declare in this module's `dependencies { }` goes in without a
version suffix. Versions flow from the workspace BOM imported at the top of
the block:

```groovy
def bom = platform('com.workato.agent:workato-agent-bom:0.1.0')
compileOnly bom
implementation bom
testImplementation bom
```

The BOM source is `../libs/workato-onprem-bom-0.1.0.xml`; the workspace
regenerates a Gradle-consumable `.pom` at build/bom-repo/ on every build.
See [`../CLAUDE.md`](../CLAUDE.md) § Dependency version management for the
authoring workflow, the versionless-import-stripping transformation, and how
to bump a version.

The one deliberate exception is **Apache HttpClient 4.5.14** — the BOM pins
only HttpClient 5, and `NtlmExtension` still uses HttpClient 4, so this one
dependency keeps an inline version with a comment pointing at the reason.
Everything else must stay versionless.

## Workato custom connectors (Ruby)

Each extension has a companion Workato custom connector (a Ruby
`connector.rb` file) under `src/main/ruby/<name>/`. The test harness that
exercises each connector lives under `src/test/ruby/<name>/` as a
**self-contained Workato SDK project** (Gemfile + settings.yaml + specs +
VCR cassettes). The connectors are what Workato recipes talk to; the agent
extensions are what the connectors talk to.

Current connectors (deliverable → test project):

- `src/main/ruby/security/connector.rb` → `src/test/ruby/security/` → calls `SecurityExtension`
- `src/main/ruby/ntlm/connector.rb`     → `src/test/ruby/ntlm/`     → calls `NtlmExtension`
- `src/main/ruby/timer/connector.rb`    → `src/test/ruby/timer/`    → calls `TimerExtension`

### When adding or editing a connector

- Keep each connector in its own subdirectory under `src/main/ruby/<name>/`.
  The file **must be named `connector.rb`** — the Workato SDK does not accept
  any other name.
- The matching `src/test/ruby/<name>/` directory holds `settings.yaml`
  (connection fields the tests load) and `spec/connector_spec.rb`. The shared
  `Gemfile` (`workato-connector-sdk` + rspec + vcr + webmock) and
  `spec_helper.rb` live one level up at `src/test/ruby/`. VCR cassettes are
  checked in under `src/test/resources/tape_library/<name>/`.
- Specs reference the connector via an absolute path pinned at the top of
  `connector_spec.rb` as
  `CONNECTOR_PATH = File.expand_path('../../../../main/ruby/<name>/connector.rb', __dir__).freeze`
  — the relative hop crosses `test → main`, so adjust it carefully if you
  nest spec files deeper.
- Any HTTP the connector performs must be stubbed via VCR cassettes under
  `src/test/resources/tape_library/<name>/`. Tests must not touch the network —
  the harness is configured with `record: :none`.
- When you add an action that calls a new URL, add a new cassette YAML under
  `src/test/resources/tape_library/<name>/` (minimal format: see the existing files).
- **One Gradle task per spec file.** Today we have `:securityRspec`, `:ntlmRspec`,
  `:timerRspec`. Any new `*_spec.rb` must get its own `:<name>Rspec` task in
  `build.gradle` (model it on the existing three — same `rubyBundleInstall`
  dependency, `workingDir` set to the per-connector dir, `commandLine
  rspecCommand(project.findProperty('spec'))`). This keeps every spec
  individually runnable from both CLI and the IDE (one Gradle run-config per
  task), and it makes the `-Pspec=…` filter behave predictably: it narrows an
  already-scoped task instead of fanning out across every spec in the project.
- **Mandatory header comment on every spec.** The first non-shebang block
  of each `*_spec.rb` must spell out how to run it from Gradle *and* from
  the IDE (both Ruby-plugin and Community/Gradle-delegate paths). Copy the
  template from `src/test/ruby/security/spec/connector_spec.rb` and swap
  the task name + an example-description substring. The comment is the
  single hand-off between a reader of the spec and someone trying to
  reproduce one failing example — do not skip it.

## Running tests

- **`./gradlew :workato-agent-extension-demo:test`** — Java JUnit tests only,
  then (via `finalizedBy`) all three Ruby RSpec suites. Both report per-test
  output; both failures surface in a single build.
- **`./gradlew :workato-agent-extension-demo:check`** — same as above plus any
  other verification tasks.
- **`./gradlew :workato-agent-extension-demo:testRubyConnectors`** — all three Ruby suites, no Java.
- **`./gradlew :workato-agent-extension-demo:securityRspec`** / `:ntlmRspec` / `:timerRspec` — one Ruby suite.

### Writing Java tests

- Tests live under `src/test/java/com/mycompany/onprem/`.
- JUnit 5 Jupiter. The Extension API mock harness is on the test classpath via
  `project(':workato-agent-extension-test')` → see
  [`../workato-agent-extension-test/README.md`](../workato-agent-extension-test/README.md)
  for the `AgentExtensionHarness` DSL. Do not hand-roll a mock of the API.
- Real HTTP in a test is fine when it's a tiny in-process server (see
  `NtlmExtensionTest` → `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`).

### Mandatory `*DeployedTest` for every extension

Every extension under `src/main/java/com/mycompany/onprem/<name>/` **must**
have a companion `<Name>ExtensionDeployedTest.java` under
`src/test/java/com/mycompany/onprem/<name>/` that runs the extension inside
the real agent via `DeployedAgentLauncher` (see
[`../workato-agent-extension-test/README.md`](../workato-agent-extension-test/README.md)
→ "Running against the real agent"). This is the only test type that
exercises classloader isolation, the real Jetty binding, and — in gateway
mode — the mTLS tunnel to Workato. `AgentExtensionHarness` cannot cover any
of these.

Conventions, copied verbatim from `SecurityExtensionDeployedTest` /
`NtlmExtensionDeployedTest` / `TimerExtensionDeployedTest`:

- `@Disabled("Manual-only — needs deployed+activated agent at AGENT_DIST; run one method per JVM.")`
  at the class level. These tests mutate process-global state inside the
  agent (singleton Spring context, Netty loops, …), so run **one `@Test`
  method per JVM invocation** from the IDE — never the whole class at once.
- A `private static final Path AGENT_DIST = Paths.get("...")` at the top,
  pointing at the local deployed+activated agent dist. The path is
  per-machine — leave it as a constant; the `@Disabled` keeps it out of CI.
- At minimum two methods:
    1. **Local-HTTP smoke test** — one or more happy-path calls via
       `openLocalAccess()` + `agent.httpPost(Controller::method, body)` /
       `agent.httpGet(Controller::method)`. Asserts the controller actually
       answers through the real agent dispatcher.
    2. **`connectsToWorkatoGateway`** — no `openLocalAccess()`, call
       `agent.awaitGatewayConnected(Duration.ofSeconds(60))`, assert the
       returned line contains `"Connected to gateway"`. Proves the dist's
       activation certs, trust store, and the extension's bean wiring do
       not block mTLS startup.
- If the extension emits outbound HTTP (webhook-style — see
  `TimerExtension` + `AgentWebhookApiV1`), start a tiny loopback
  `com.sun.net.httpserver.HttpServer` sink in the test to capture the
  delivered payload and headers, and assert on them.

### Writing Ruby tests

- Test framework: RSpec + VCR + WebMock (all declared in the shared
  `src/test/ruby/Gemfile`; `Gemfile.lock` and `spec_helper.rb` live next to
  it, each per-connector `spec/spec_helper.rb` is a one-line
  `require_relative` wrapper).
- Pattern per spec:
  ```ruby
  CONNECTOR_PATH = File.expand_path('../../../../main/ruby/<name>/connector.rb', __dir__).freeze

  let(:settings)  { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file(CONNECTOR_PATH, settings) }
  ```
  Then invoke:
    - `connector.actions.<name>.execute(settings, input_hash)`
    - `connector.methods.<name>(args...)`
    - `connector.test(settings)` for the connection test lambda.
- Wrap HTTP-touching tests in `VCR.use_cassette('name') do ... end`. The
  cassette file is `src/test/resources/tape_library/<connector>/<name>.yml`
  (the shared `spec_helper.rb` resolves the `<connector>` subdir from the
  CWD basename).
- No real network: `spec_helper.rb` sets `record: :none`, so an unmocked
  request fails loudly.
- To iterate locally without going through Gradle: `cd src/test/ruby/<name>
  && bundle install && bundle exec rspec`.
