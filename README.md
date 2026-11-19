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
| **[`workato-agent-extension-api/`](workato-agent-extension-api/README.md)**   | Customers writing an extension.                                   | The public API your `@RestController` compiles against (`AgentExtensionApiV1`, `AgentExtensionContextV1`, `AgentWebhookApiV1`). The agent provides the implementation at runtime.                      |
| **[`workato-agent-extension-test/`](workato-agent-extension-test/README.md)** | Customers testing an extension.                                   | Two complementary harnesses: `AgentExtensionHarness` (fast in-memory `MockMvc` runtime, no agent needed) and `DeployedAgentLauncher` (runs the real agent in-process against a deployed dist on disk). |
| **[`workato-agent-extension-demo/`](workato-agent-extension-demo/README.md)** | Anyone looking for a worked example — or evolving the API itself. | Three reference extensions (`security`, `ntlm`, `timer`) plus their Workato custom connectors (Ruby), JUnit tests, and VCR cassettes. Exercises every pattern the API and the harness expose.          |

The `-api` and `-test` modules are built from source here and (future work)
published to Nexus; the agent consumes them from there. The `-demo` module is
not published — it exists to exercise the other two and to serve as a
reference customers can clone from.

## Quick start — write an extension

1. Take a dependency on `workato-agent-extension-api` (compile-only; the
   agent provides it at runtime) and on `workato-agent-extension-test`
   (test-only).
2. Write a Spring `@RestController`. Inject `AgentExtensionApiV1` to read the
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
- **Ruby:** 3.4.5 (pinned via `.tool-versions` for asdf). Only the demo
  module uses Ruby — the sibling connector tests run via `bundle exec rspec`
  through Gradle.

See [`CLAUDE.md`](CLAUDE.md) for the workspace-level toolchain notes and
pinned dependency versions, and each module's own `CLAUDE.md` for
maintainer-facing conventions.

## Repository layout

```
./
├── build.gradle                       # Shared config + ext{} version pins
├── settings.gradle                    # include 'workato-agent-extension-{api,test,demo}'
├── CLAUDE.md                          # Workspace overview for Claude / maintainers
├── gradle/wrapper/                    # Gradle 9.9.1
├── workato-agent-extension-api/       # Published to Nexus (future)
│   ├── README.md                      # ← customer-facing API guide
│   ├── CLAUDE.md                      # ← maintainer invariants
│   └── src/main/java/com/workato/agent/extension/api/
├── workato-agent-extension-test/      # Published to Nexus (future)
│   ├── README.md                      # ← customer-facing test-harness guide
│   ├── CLAUDE.md                      # ← maintainer invariants
│   └── src/main/java/com/workato/agent/extension/test/
└── workato-agent-extension-demo/      # Not published — reference only
    ├── README.md                      # ← customer-facing six-step walkthrough
    ├── CLAUDE.md                      # ← demo-specific conventions (dep scoping, Ruby, DeployedTest)
    └── src/
        ├── main/java/com/mycompany/onprem/{security,ntlm,timer}/
        ├── main/ruby/{security,ntlm,timer}/connector.rb
        ├── test/java/com/mycompany/onprem/{security,ntlm,timer}/
        ├── test/resources/extensions/*.yml          # YAML fixtures for AgentExtensionHarness
        ├── test/resources/tape_library/*/           # VCR cassettes for Ruby RSpec
        └── test/ruby/                               # Shared Gemfile + per-connector specs
```
