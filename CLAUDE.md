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
`connector.rb` file) under `src/main/ruby/<name>/`. Each directory is a
**self-contained Workato SDK project** (connector + Gemfile + settings.yaml +
specs + VCR cassettes). The connectors are what Workato recipes talk to; the
agent extensions are what the connectors talk to.

Current connectors:

- `src/main/ruby/security/` → calls `SecurityExtension`
- `src/main/ruby/ntlm/` → calls `NtlmExtension`

### When adding or editing a connector

- Keep each connector in its own subdirectory under `src/main/ruby/`.
  The file **must be named `connector.rb`** — the Workato SDK does not accept
  any other name.
- `settings.yaml` holds the connection fields the tests need.
- Specs go in `spec/connector_spec.rb`. Any HTTP the connector performs must be
  stubbed via VCR cassettes under `tape_library/`. Tests must not touch the
  network — the harness is configured with `record: :none`.
- When you add an action that calls a new URL, add a new cassette YAML under
  `tape_library/` (minimal format: see the existing files).

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

### Writing Ruby tests

- Test framework: RSpec + VCR + WebMock (all declared in each
  `src/main/ruby/<name>/Gemfile`).
- Pattern per spec:
  ```ruby
  let(:settings)  { Workato::Connector::Sdk::Settings.from_default_file }
  let(:connector) { Workato::Connector::Sdk::Connector.from_file('connector.rb', settings) }
  ```
  Then invoke:
    - `connector.actions.<name>.execute(settings, input_hash)`
    - `connector.methods.<name>(args...)`
    - `connector.test(settings)` for the connection test lambda.
- Wrap HTTP-touching tests in `VCR.use_cassette('name') do ... end`. The
  cassette file is `tape_library/<name>.yml`.
- No real network: `spec_helper.rb` sets `record: :none`, so an unmocked
  request fails loudly.
- To iterate locally without going through Gradle: `cd src/main/ruby/<name>
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
