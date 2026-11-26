# Workato Agent Extensions

Public Extension API for the Workato on-prem agent (OPA), the matching test
harness, and a set of reference sample extensions — all in one multi-module
Gradle workspace.

An **extension** is a customer-authored Spring `@RestController` that the agent
mounts behind the Workato tunnel at `/ext/{profile}/...`, so a Workato recipe
can call code that runs inside the customer's firewall while still looking
like a normal Workato connector action from the recipe editor.

## Modules

| Module                                                                        | Who uses it                                                       | What's in it                                                                                                                                                                                           |
|-------------------------------------------------------------------------------|-------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **[`workato-agent-extension-api/`](workato-agent-extension-api/README.md)**   | Customers writing an extension.                                   | The public API your `@RestController` compiles against (`AgentExtensionV1`, `AgentExtensionContextV1`, `AgentWebhookV1`). The agent provides the implementation at runtime.                            |
| **[`workato-agent-extension-test/`](workato-agent-extension-test/README.md)** | Customers testing an extension.                                   | Two complementary harnesses: `AgentExtensionHarness` (fast in-memory `MockMvc` runtime, no agent needed) and `DeployedAgentLauncher` (runs the real agent in-process against a deployed dist on disk). |
| **[`workato-agent-extension-demo/`](workato-agent-extension-demo/README.md)** | Anyone looking for a worked example — or evolving the API itself. | Three reference extensions (`security`, `ntlm`, `timer`) plus their Workato custom connectors (Ruby), JUnit tests, and VCR cassettes. Exercises every pattern the API and the harness expose.          |

All three modules publish to GitHub Packages
(`https://maven.pkg.github.com/workato/opa-extensions`) with three
classifiers each: main jar, `-sources`, and `-javadoc`. The `-api` and
`-test` modules ship as regular libraries. The `-demo` module's main jar is
a shaded fat jar (drop into `lib_ext/`); its `-sources` classifier carries
Java code plus the Ruby custom connectors under a top-level `ruby/`
directory, so a customer can clone the reference material straight from
Maven coordinates.

Consuming the artifacts requires a GitHub personal access token with the
`read:packages` scope — GitHub Packages Maven requires authentication even
for public reads. Point Gradle/Maven at
`https://maven.pkg.github.com/workato/opa-extensions` with your GitHub
login and token as the repo credentials.

## Quick start — write an extension

1. Take a dependency on `workato-agent-extension-api` (compile-only; the
   agent provides it at runtime) and on `workato-agent-extension-test`
   (test-only).
2. Write a Spring `@RestController`. Inject `AgentExtensionV1` to read the
   endpoint's YAML configuration and (optionally) send webhooks back to a
   Workato recipe. See [`workato-agent-extension-api/README.md`](workato-agent-extension-api/README.md)
   for the complete API reference and allowed-packages rules.
3. Write a JUnit test using `AgentExtensionHarness` — it wires your
   controller into a hermetic `MockMvc` context, no agent needed. See
   [`workato-agent-extension-test/README.md`](workato-agent-extension-test/README.md).
4. Package as a fat jar, drop it into `<agent>/ext/`, add an entry under
   `extensions:` in the agent's `config.yml`.

Three worked examples sit in
[`workato-agent-extension-demo/`](workato-agent-extension-demo/README.md),
each with its own README explaining which API features it demonstrates.

## Building

```sh
./gradlew build          # Build + test every module (Java + Ruby)
./gradlew test           # Run every test suite
./gradlew :<module>:build # Build one module only
```

- **Java:** 21 target. Gradle wrapper is 9.9.1 (supports JDK 25+/26 as the
  launcher JVM).
- **Ruby:** 3.4+ (enforced by the Gemfile's `ruby '~> 3.4.0'` directive —
  no `.tool-versions` or `.ruby-version` file in the repo; use whichever
  Ruby manager you prefer or system Ruby). Only the demo module uses Ruby —
  the sibling connector tests run via `bundle exec rspec` through Gradle.

### Dependency versions

Every third-party version is pinned by a single BOM at
[`libs/workato-onprem-bom-0.1.0.xml`](libs/workato-onprem-bom-0.1.0.xml) and
consumed by every module as
`platform('com.workato.agent:workato-agent-bom:0.1.0')`. Module build files
declare `group:name` without versions. To bump a library, edit the BOM and
rebuild.

See [`CLAUDE.md`](CLAUDE.md) § Dependency version management for the
authoring workflow (GAV substitution, versionless-import stripping, Spring
7 floor), and each module's own `CLAUDE.md` for maintainer-facing
conventions.

### Versioning and publishing

The base version lives in [`gradle.properties`](gradle.properties)
(`version=0.1.0`); the `version.suffix` property flips the build between
release and SNAPSHOT:

| How you invoke Gradle                                   | Effective version                                                                                      | Where it lands                                             |
|---------------------------------------------------------|--------------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| `./gradlew publish` (no suffix flag)                    | auto-detect from git: `0.1.0-${branch}-SNAPSHOT`, or `0.1.0-SNAPSHOT` on master / main / detached HEAD | GitHub Packages (SNAPSHOT semantics from the version)       |
| `./gradlew publish -Pversion.suffix=SNAPSHOT`           | `0.1.0-SNAPSHOT`                                                                                       | GitHub Packages                                             |
| `./gradlew publish -Pversion.suffix=feature-x-SNAPSHOT` | `0.1.0-feature-x-SNAPSHOT`                                                                             | GitHub Packages                                             |
| `./gradlew publish -Pversion.suffix=`                   | `0.1.0` (explicit release)                                                                             | GitHub Packages                                             |
| `./gradlew publishToMavenLocal`                         | same resolution rules                                                                                  | `~/.m2/repository/com/workato/agent/`                       |
| `./gradlew publish githubRelease -Pversion.suffix=`     | `0.1.0` + GitHub Release `v0.1.0` with every module's `*.jar` attached                                 | GitHub Packages + github.com/workato/opa-extensions/releases |

Branch name on `release-0.1.0` → the auto-detected SNAPSHOT version becomes
`0.1.0-release-0.1.0-SNAPSHOT`, so a published `-SNAPSHOT` artifact always
identifies the branch it was built from.

Publishing runs in CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
as the `publish` job (`needs: build`, so a red test suite blocks every
upload). Every push triggers a SNAPSHOT publish; a manual *Actions → CI →
Run workflow* with `release: true` triggers a release publish plus a
GitHub Release. Credentials are the workflow's auto-provided `GITHUB_TOKEN`
(scoped via `permissions: packages: write`) plus `github.actor` — no
additional repo secrets are required. See [`CLAUDE.md`](CLAUDE.md)
§ Versioning and publishing for the full table and local-override
instructions.

## Repository layout

```
./
├── build.gradle                       # Shared config + maven repo pointing at build/bom-repo
├── settings.gradle                    # Module includes + BOM materialisation
├── CLAUDE.md                          # Workspace overview for Claude / maintainers
├── libs/
│   └── workato-onprem-bom-0.1.0.xml   # Single source of truth for dependency versions
├── build/bom-repo/                    # Generated (gitignored) Maven layout serving the BOM
├── gradle/wrapper/                    # Gradle 9.9.1
├── workato-agent-extension-api/       # Published to GitHub Packages (jar + sources + javadoc)
│   ├── README.md                      # ← customer-facing API guide
│   ├── CLAUDE.md                      # ← maintainer invariants
│   └── src/main/java/com/workato/agent/extension/api/
├── workato-agent-extension-test/      # Published to GitHub Packages (jar + sources + javadoc)
│   ├── README.md                      # ← customer-facing test-harness guide
│   ├── CLAUDE.md                      # ← maintainer invariants
│   └── src/main/java/com/workato/agent/extension/test/
└── workato-agent-extension-demo/      # Published to GitHub Packages (shaded fat jar + sources incl. Ruby + javadoc)
    ├── README.md                      # ← customer-facing six-step walkthrough
    ├── CLAUDE.md                      # ← demo-specific conventions (dep scoping, Ruby, DeployedTest)
    └── src/
        ├── main/java/com/workato/agent/extension/demo/{security,ntlm,timer}/
        ├── main/ruby/{security,ntlm,timer}/connector.rb
        ├── test/java/com/workato/agent/extension/demo/{security,ntlm,timer}/
        ├── test/resources/extensions/*.yml          # YAML fixtures for AgentExtensionHarness
        ├── test/resources/tape_library/*/           # VCR cassettes for Ruby RSpec
        └── test/ruby/                               # Shared Gemfile + per-connector specs
```
