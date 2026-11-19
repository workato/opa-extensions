# CLAUDE.md — workspace root

Workspace-level orientation for Claude agents. Per-module guidance lives in
each module's own `CLAUDE.md` — read the one that matches what you are
touching.

## Workspace

OPA (On-Premise Agent) extension workspace. Three Gradle modules, all compiled
from source and built together:

| Module                              | Purpose                                                                                                                                                                                                                                         | Per-module guidance                                                                                                                                                                     |
|-------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`workato-agent-extension-api/`**  | Public Extension API that customer extensions compile against. Published to Nexus (future work); the agent (OPA) consumes it from there and provides the interfaces at runtime.                                                                 | [`workato-agent-extension-api/CLAUDE.md`](workato-agent-extension-api/CLAUDE.md) + customer-facing [`workato-agent-extension-api/README.md`](workato-agent-extension-api/README.md)     |
| **`workato-agent-extension-test/`** | Test-harness library. Depends on `-api`. Customers put it on their test classpath to drive their extension through a Spring `MockMvc` runtime without launching the real agent. Also publishable to Nexus.                                      | [`workato-agent-extension-test/CLAUDE.md`](workato-agent-extension-test/CLAUDE.md) + customer-facing [`workato-agent-extension-test/README.md`](workato-agent-extension-test/README.md) |
| **`workato-agent-extension-demo/`** | Sample extensions (`security`, `ntlm`, `timer`) plus their JUnit tests and Workato custom connectors (Ruby). Not published — serves as an end-to-end exerciser of the two artifacts above and as a reference implementation for customer teams. | [`workato-agent-extension-demo/CLAUDE.md`](workato-agent-extension-demo/CLAUDE.md) + customer-facing [`workato-agent-extension-demo/README.md`](workato-agent-extension-demo/README.md) |

Both `-api` and `-test` were previously developed inside the OPA repo
(`modules/extension-api`, `modules/extension-test`). They were moved here so
the Extension API can evolve independently of the agent release cycle. Any
cross-reference inside the module-level CLAUDE.md files to
`modules/agent-core/…` or `modules/agent-launcher/…` points into the OPA
repo (`~/Projects/opa/`), which consumes these modules as published
artifacts.

## Layout

```
./
├── build.gradle                       # Root: group/version/repos + shared ext{} versions
├── settings.gradle                    # include 'workato-agent-extension-{api,test,demo}'
├── gradle/wrapper/                    # Gradle 9.9.1
├── workato-agent-extension-api/       # (see module's CLAUDE.md + README.md)
├── workato-agent-extension-test/      # (see module's CLAUDE.md + README.md)
└── workato-agent-extension-demo/      # (see module's CLAUDE.md + README.md)
```

The root `build.gradle` holds nothing but shared configuration (group,
version, repos, Java toolchain, dependency-version constants in
`subprojects { ext { … } }`). Every module reads versions through
`${springVersion}`, `${jacksonVersion}` etc.

## Shared version pins (root build.gradle `ext`)

Pins mirror OPA's version-catalog so runtime behaviour matches the agent's
exactly. Current values:

- `springVersion` = 7.0.9
- `jacksonVersion` = 3.2.2 (Jackson 3, `tools.jackson.*` namespace)
- `jakartaServletVersion` = 6.1.0
- `slf4jVersion` = 2.0.18
- `logbackVersion` = 1.5.38
- `junitVersion` / `junitPlatformVersion` = 6.1.3
- `assertjVersion` = 3.27.7

Spring 7 is the floor because it is the first Spring version with full
Jackson 3 (`tools.jackson.*`) support. A downgrade to Spring 6 breaks
MockMvc-driven JSON tests (415 Unsupported Media Type).

## Toolchain

- Java 21 target; Gradle 9.9.1 (wrapper). Gradle must run on a supported JDK;
  the 9.x wrapper supports JDK 25+/26.
- Ruby 3.4.5 (pinned via `.tool-versions` for asdf at the repo root). Only
  the demo module uses Ruby — see
  [`workato-agent-extension-demo/CLAUDE.md`](workato-agent-extension-demo/CLAUDE.md)
  for connector conventions.

## Running tests across the workspace

- `./gradlew build` — full build across all three modules; runs Java tests
  and (inside `:workato-agent-extension-demo`) the Ruby suites.
- `./gradlew test` — runs every module's JUnit suite plus the demo's Ruby
  RSpec suites.
- `./gradlew :<module>:test` — one module only.
- `./gradlew :<module>:build` — one module only, fully.

Per-module test commands and conventions live in each module's `CLAUDE.md`.
