# CLAUDE.md

Guidance for Claude agents working in this repository.

## Project

OPA (On-Premise Agent) extensions. Custom Java classes compiled against the
agent's Extension API and loaded by the agent at runtime. Sources live under
`src/main/java/com/mycompany/onprem/`.

## Extension API & test-mock JARs

The compile and runtime classpaths depend on two JARs shipped with the agent
distribution and placed in `extension-api/`:

- `extension-api-<version>.jar` — the Extension API (compile dependency).
- `extension-test-<version>.jar` — a mock/harness used for writing unit tests
  against the API (runtime dependency).

Both JARs **must come from the same agent distribution** (identical version
suffix). The Gradle `validateExtensionJars` task enforces this and fails the
build if the directory is missing, a JAR is absent, versions are mixed, or
multiple versions of either JAR are present.

### Dependency scoping — do not revert

The `jar` task produces a **minimal fat jar**: only libraries actually used
by extension code are shaded in. Anything the agent provides at runtime
(`extension-api`, Spring, Jackson, Jakarta, SLF4J) is declared `compileOnly`
and stays out of `build/libs/opa-extensions-<version>.jar`. Shading
agent-provided types would cause classloader conflicts in the agent — see
`extension-api/README-extension-api.md` → "do not shade it into your JAR".

Scoping rules — follow them when adding a new dependency:

- **Shaded (`implementation`)** — any new third-party library that the agent
  does NOT already provide. Today: `commons-codec`, `commons-lang3`. Add a
  trailing comment pointing at the extension that uses the library.
- **Provided by the agent (`compileOnly`)** — anything from Spring, Jackson,
  Jakarta, SLF4J, Apache HttpClient, or further `com.workato.*` packages.
  Compile against it, do not shade.
- **Test-only (`testImplementation`)** — anything only the test harness
  needs. `jackson-dataformat-yaml` (used by `AgentExtensionHarness` to parse
  YAML fixtures) and `extension-test-*.jar` live here.

The `configurations { testImplementation.extendsFrom compileOnly }` block at
the top of `dependencies { }` forwards every `compileOnly` entry onto the
test classpath — Gradle does NOT do this automatically, and without this
line `AgentExtensionHarness` loses Spring at test runtime.

Verify after any dependency change: `./gradlew clean jar && unzip -l
build/libs/opa-extensions-*.jar | grep -c org/springframework` must print
`0`. If a Spring/Jackson/SLF4J class is in the jar you shaded something you
shouldn't have.

## Authoritative documentation — read before coding

The Gradle build extracts the README from each JAR into `extension-api/` on
every build:

- **`extension-api/README-extension-api.md`** — the contract for writing an
  extension: available interfaces, lifecycle, injection points, allowed
  packages, and usage examples. Any new extension class under
  `src/main/java/com/mycompany/onprem/` **must** follow this document.
- **`extension-api/README-extension-test.md`** — the contract for writing
  tests against a mocked Extension API: available fakes/builders, how to wire
  the mock runtime, and example test patterns. Any new test under `src/test/`
  **must** follow this document.

These files are the single source of truth for *how* to use the API and *how*
to test against it. They are regenerated from the JARs on every build and
therefore always match the version currently on the classpath — prefer them
over any external notes or stale examples.

### Required workflow for Claude agents

Before writing or modifying an extension or its tests:

1. Run `./gradlew validateExtensionJars extractExtensionReadmes` (or any build
   task — both run as part of `compileJava`) to ensure the READMEs are
   present and up to date.
2. Read `extension-api/README-extension-api.md` end-to-end when touching
   production code under `src/main/java/`.
3. Read `extension-api/README-extension-test.md` end-to-end when touching or
   adding tests under `src/test/`.
4. Follow the patterns and constraints documented there. Do not invent
   alternative API usage, do not mock the Extension API by hand — use the
   facilities provided by `extension-test-*.jar` as described in its README.

If a README is missing, the JARs have not been copied from the agent
distribution yet. Ask the user to copy them rather than guessing at the API.

## Workato custom connectors (Ruby)

Each on-prem extension has a companion Workato custom connector (a Ruby
`connector.rb` file) under `src/main/ruby/<name>/`. The test harness that
exercises each connector lives under `src/test/ruby/<name>/` as a
**self-contained Workato SDK project** (Gemfile + settings.yaml + specs + VCR
cassettes). The connectors are what Workato recipes talk to; the agent
extensions are what the connectors talk to.

Current connectors (deliverable → test project):

- `src/main/ruby/security/connector.rb` → `src/test/ruby/security/` → calls `SecurityExtension`
- `src/main/ruby/ntlm/connector.rb`     → `src/test/ruby/ntlm/`     → calls `NtlmExtension`

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
- **One Gradle task per spec file.** Today we have `:securityRspec` ↔
  `src/test/ruby/security/spec/connector_spec.rb` and `:ntlmRspec` ↔
  `src/test/ruby/ntlm/spec/connector_spec.rb`. Any new `*_spec.rb` must get
  its own `:<name>Rspec` task in `build.gradle` (model it on the existing
  two — same `rubyBundleInstall` dependency, `workingDir` set to the
  per-connector dir, `commandLine rspecCommand(project.findProperty('spec'))`).
  This keeps every spec individually runnable from both CLI and the IDE
  (one Gradle run-config per task), and it makes the `-Pspec=…` filter
  behave predictably: it narrows an already-scoped task instead of fanning
  out across every spec in the project.
- **Mandatory header comment on every spec.** The first non-shebang block
  of each `*_spec.rb` must spell out how to run it from Gradle *and* from
  the IDE (both Ruby-plugin and Community/Gradle-delegate paths). Copy the
  template from `src/test/ruby/security/spec/connector_spec.rb` and swap
  the task name + an example-description substring. The comment is the
  single hand-off between a reader of the spec and someone trying to
  reproduce one failing example — do not skip it.

## Running tests

- **`./gradlew test`** — runs Java JUnit tests (`:test`) and then both Ruby
  RSpec suites (`:securityRspec`, `:ntlmRspec`) via `finalizedBy`. Both report
  per-test output; both failures surface in a single build.
- **`./gradlew check`** — same as above plus any other verification tasks.
- **`./gradlew :test`** — Java only.
- **`./gradlew testRubyConnectors`** — both Ruby suites only.
- **`./gradlew :securityRspec`** / **`./gradlew :ntlmRspec`** — one Ruby suite.

### Writing Java tests

- Tests live under `src/test/java/com/mycompany/onprem/`.
- JUnit 5 Jupiter. The Extension API mock harness is on the test classpath via
  `extension-test-<version>.jar` → see `extension-api/README-extension-test.md`
  for the `AgentExtensionHarness` DSL. Do not hand-roll a mock of the API.
- Real HTTP in a test is fine when it's a tiny in-process server (see
  `NtlmExtensionTest` → `com.sun.net.httpserver.HttpServer` on `127.0.0.1:0`).

### Mandatory `*DeployedTest` for every extension

Every extension under `src/main/java/com/mycompany/onprem/<name>/` **must**
have a companion `<Name>ExtensionDeployedTest.java` under
`src/test/java/com/mycompany/onprem/<name>/` that runs the extension inside
the real agent via `DeployedAgentLauncher` (see `README-extension-test.md` →
"Running against the real agent"). This is the only test type that exercises
classloader isolation, the real Jetty binding, and — in gateway mode — the
mTLS tunnel to Workato. `AgentExtensionHarness` cannot cover any of these.

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
       `agent.httpGet(Controller::method)`. Asserts controller actually
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

## Toolchain

- Java 21, Gradle 8.10.2.
- Ruby 3.2.8 (pinned via `.tool-versions` for asdf). The Workato SDK gem
  (`workato-connector-sdk`) is self-contained — no Workato cloud / account
  needed to run tests.
- The Gradle `bundleBin` helper locates `bundle` in this order:
  `$BUNDLE_BIN` → `~/.asdf/shims/bundle` → `bundle` on PATH. If you're on a
  CI runner that provisions Ruby a different way, set `BUNDLE_BIN` to the
  resulting bundler executable.

## Build

- `./gradlew build` — full build; triggers JAR validation, README extraction,
  Java tests, and Ruby tests.
- `./gradlew validateExtensionJars` — JAR presence/version check only.
- `./gradlew extractExtensionReadmes` — refresh the two README files.
