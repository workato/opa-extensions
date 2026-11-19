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
├── build.gradle                       # Root: group/version, repos (incl. the generated bom-repo)
├── settings.gradle                    # module includes + BOM regeneration from libs/*.xml
├── libs/
│   └── workato-onprem-bom-0.1.0.xml   # curated BOM — the single source of truth for versions
├── build/bom-repo/                    # generated, gitignored — Maven layout serving the BOM
├── gradle/wrapper/                    # Gradle 9.9.1
├── workato-agent-extension-api/       # (see module's CLAUDE.md + README.md)
├── workato-agent-extension-test/      # (see module's CLAUDE.md + README.md)
└── workato-agent-extension-demo/      # (see module's CLAUDE.md + README.md)
```

## Dependency version management

**Single source of truth:** `libs/workato-onprem-bom-0.1.0.xml`. Every
compiled dependency version comes from this Maven-style
`<dependencyManagement>` POM. Module build.gradle files declare `group:name`
without versions; the BOM supplies them via
`platform('com.workato.agent:workato-agent-bom:0.1.0')`.

### How it is wired

1. **Author.** The BOM is a plain Maven POM with `<packaging>pom</packaging>`
   and a `<dependencyManagement>` block. It mirrors OPA's internal
   version-catalog (`modules/version-catalog` in the OPA repo), so runtime
   behaviour matches the agent's exactly. It also imports
   `org.springframework:spring-framework-bom` via `<scope>import</scope>`,
   which gives transitive Spring version alignment.
2. **Materialise.** `settings.gradle` runs at initialisation and generates
   `build/bom-repo/com/workato/agent/workato-agent-bom/0.1.0/workato-agent-bom-0.1.0.pom`
   from the curated .xml. Regeneration happens only when the source is newer
   than the target. Two transformations:
    - **GAV substitution.** The published .xml has `<groupId></groupId>`,
      `<artifactId>version-catalog</artifactId>`, `<version>unspecified</version>`
      (Gradle's defaults when publishing without configured coordinates).
      We rewrite them to `com.workato.agent:workato-agent-bom:0.1.0`.
    - **Versionless-import stripping.** The source contains three
      `<dependency>` blocks with `<scope>import</scope>` + `<type>pom</type>`
      but *no* `<version>` (grpc-bom, protobuf-bom, guava-bom — their
      versions would normally be inherited from a parent POM, which this
      standalone BOM does not have). Gradle 9 **silently rejects the entire
      POM** when it encounters any such entry — no parse error, just a
      `FAILED` in the dependency report. We strip them. No module in this
      workspace uses grpc / protobuf / guava, so the loss is harmless.
3. **Expose.** Root `build.gradle` declares
   `maven { url = rootProject.file('build/bom-repo').toURI() }` ahead of
   `mavenCentral()`, so Gradle resolves `com.workato.agent:workato-agent-bom:0.1.0`
   locally. The repo also serves whatever module metadata subprojects need
   from Maven Central (e.g. spring-framework-bom itself).
4. **Consume.** Each module declares the BOM once per configuration where it
   needs managed versions:
   ```groovy
   dependencies {
       def bom = platform('com.workato.agent:workato-agent-bom:0.1.0')
       api bom                 // or compileOnly / implementation / testImplementation
       testImplementation bom
       // Then: group:name without versions. Example:
       api 'org.springframework:spring-webmvc'
       api 'tools.jackson.core:jackson-databind'
   }
   ```

### Bumping a version

1. Edit `libs/workato-onprem-bom-0.1.0.xml`, change the `<version>` on the
   target entry.
2. Rebuild. `settings.gradle` sees the newer mtime on the .xml and
   regenerates the .pom; Gradle's module cache sees the new POM content and
   re-resolves.
3. If it is a new library the modules did not reference before, also add a
   `group:name` entry to the relevant module's `dependencies { }`.

### Deps not managed by the BOM

- **Apache HttpClient 4.5.14** (`org.apache.httpcomponents:httpclient`) —
  the BOM pins only HttpClient 5. `NtlmExtension` in the demo module still
  imports `org.apache.http.*` (HttpClient 4), so the demo's `build.gradle`
  carries an explicit `compileOnly 'org.apache.httpcomponents:httpclient:4.5.14'`
  with a comment. Drop the explicit pin once `NtlmExtension` migrates to
  HttpClient 5.

### Baseline versions (as of the current BOM revision)

Mirrors OPA's `modules/version-catalog` pins:

- Spring Framework: 7.0.9 (via `spring-framework-bom` import — first Spring
  with full Jackson 3 support; downgrade breaks MockMvc-driven JSON tests
  with 415 Unsupported Media Type)
- Jackson 3 (`tools.jackson.*`): 3.2.2
- Jackson 2 (`com.fasterxml.jackson.*`): 2.22.2 (still used by demo DTOs)
- Jakarta Servlet API: 6.1.0
- Jakarta Inject API: 2.0.1
- SLF4J: 2.0.18 | Logback: 1.5.38
- JUnit Jupiter + Platform: 6.1.3
- AssertJ: 3.27.7
- commons-codec: 1.22.1 | commons-lang3: 3.20.0

## Toolchain

- Java 21 target; Gradle 9.9.1 (wrapper). Gradle must run on a supported JDK;
  the 9.x wrapper supports JDK 25+/26.
- Ruby 3.4+ (enforced by the demo's `Gemfile` via `ruby '~> 3.4.0'` — the
  repo does NOT ship a `.tool-versions` / `.ruby-version` file, so whichever
  version manager you prefer, or system Ruby, works as long as the active
  Ruby satisfies the constraint). Only the demo module uses Ruby — see
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
