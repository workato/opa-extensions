# CLAUDE.md — workspace root

Workspace-level orientation for Claude agents. Per-module guidance lives in
each module's own `CLAUDE.md` — read the one that matches what you are
touching.

## Workspace

OPA (On-Premise Agent) extension workspace. Three Gradle modules, all compiled
from source and built together:

| Module                              | Purpose                                                                                                                                                                                                                                                           | Per-module guidance                                                                                                                                                                     |
|-------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`workato-agent-extension-api/`**  | Public Extension API that customer extensions compile against. Published to Nexus as a regular library (jar + sources + javadoc); the agent (OPA) consumes it from there and provides the interfaces at runtime.                                                  | [`workato-agent-extension-api/CLAUDE.md`](workato-agent-extension-api/CLAUDE.md) + customer-facing [`workato-agent-extension-api/README.md`](workato-agent-extension-api/README.md)     |
| **`workato-agent-extension-test/`** | Test-harness library. Depends on `-api`. Customers put it on their test classpath to drive their extension through a Spring `MockMvc` runtime without launching the real agent. Published to Nexus as a regular library.                                          | [`workato-agent-extension-test/CLAUDE.md`](workato-agent-extension-test/CLAUDE.md) + customer-facing [`workato-agent-extension-test/README.md`](workato-agent-extension-test/README.md) |
| **`workato-agent-extension-demo/`** | Sample extensions (`security`, `ntlm`, `timer`) plus their JUnit tests and Workato custom connectors (Ruby). Published to Nexus as a shaded fat jar — the primary role is end-to-end exercise of the two artifacts above and as a reference customers clone from. | [`workato-agent-extension-demo/CLAUDE.md`](workato-agent-extension-demo/CLAUDE.md) + customer-facing [`workato-agent-extension-demo/README.md`](workato-agent-extension-demo/README.md) |

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

## Versioning and publishing

### Version resolution

The artifact version is computed at configuration time in root `build.gradle`
from three inputs:

| Input          | Source                                                                                                                                       | Example          |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| `baseVersion`  | `gradle.properties` (`baseVersion=0.1.0`). Override with `-PbaseVersion=...`.                                                                | `0.1.0`          |
| `release` flag | `-Prelease` on the Gradle command line.                                                                                                      | present / absent |
| Current branch | Env vars first (`GITHUB_REF_NAME`, `CI_COMMIT_REF_NAME`, `BUILD_SOURCEBRANCHNAME`), else `git rev-parse --abbrev-ref HEAD` run in `rootDir`. | `release-0.1.0`  |

The three combine as:

| Flag / branch                 | Effective version                            | Target repository (Nexus) |
|-------------------------------|----------------------------------------------|---------------------------|
| `-Prelease`                   | `${baseVersion}`                             | `maven-releases`          |
| no flag, on `master` / `main` | `${baseVersion}-SNAPSHOT`                    | `maven-snapshots`         |
| no flag, any other branch     | `${baseVersion}-${sanitizedBranch}-SNAPSHOT` | `maven-snapshots`         |
| no flag, branch undetectable  | `${baseVersion}-SNAPSHOT` (fallback)         | `maven-snapshots`         |

`sanitizedBranch` replaces anything outside `[A-Za-z0-9._-]` with `-` and
collapses consecutive dashes — Maven versions cannot contain `/` or spaces.
The sanitised branch is directly recoverable from the published version
string, so any SNAPSHOT in Nexus can be traced back to the branch it came
from.

### Publication layout

| Module                         | Publication content                                   | POM dependencies                                                      |
|--------------------------------|-------------------------------------------------------|-----------------------------------------------------------------------|
| `workato-agent-extension-api`  | main jar + sources + javadoc (from `components.java`) | `platform(workato-agent-bom)` + Jackson `compileOnly`                 |
| `workato-agent-extension-test` | main jar + sources + javadoc (from `components.java`) | `platform(workato-agent-bom)` + api-module GAV + Spring/Jackson/JUnit |
| `workato-agent-extension-demo` | shaded fat jar only (`tasks.named('jar')`)            | none — the fat jar has everything bundled                             |

All three publications land in the Workato Nexus
(`nexus.infra.wktodev.net/repository/maven-releases` or `/maven-snapshots`)
when `./gradlew publish` runs. `publishToMavenLocal` installs them under
`~/.m2/repository/com/workato/agent/`.

### Credentials

**Primary path — GitHub Actions.** Publish does not happen from developer
laptops; it runs in `.github/workflows/ci.yml` as the `publish` job (gated
by `needs: build`, so a red test suite blocks every upload). The job reads
credentials from repository secrets:

| GitHub secret    | Injected as Gradle property                                                      |
|------------------|----------------------------------------------------------------------------------|
| `NEXUS_USERNAME` | `ORG_GRADLE_PROJECT_nexusUsername` → `nexusUsername`                             |
| `NEXUS_PASSWORD` | `ORG_GRADLE_PROJECT_nexusPassword` → `nexusPassword`                             |
| `GITHUB_TOKEN`   | provided automatically, consumed by the `gh` CLI inside the `githubRelease` task |

Configure `NEXUS_USERNAME` / `NEXUS_PASSWORD` once at the repo level
(*Settings → Secrets and variables → Actions*). The workflow runs on every
branch push and on manual dispatch (see "Release commands" below).

**Secondary path — local override.** A maintainer running `./gradlew publish`
locally can still supply credentials via the usual Gradle property lookup:
CLI flags (`-PnexusUsername=... -PnexusPassword=...`),
`~/.gradle/gradle.properties`, or the same `ORG_GRADLE_PROJECT_*` env vars.
Missing credentials only break `publish`; local builds,
`publishToMavenLocal`, and SNAPSHOT dev loops all work without them.

### Release commands

**From GitHub Actions (normal path).** One workflow (`ci.yml`), two jobs
(`build` → `publish`); `publish` runs only if `build` succeeds, so a failed
test suite means no Nexus upload and no GitHub Release:

| Trigger                                                       | Effect                                                                                                                            |
|---------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| Pull request                                                  | `build` only — PRs never publish (reviewed via build status).                                                                     |
| Push to any branch                                            | `build` + SNAPSHOT publish to Nexus `maven-snapshots`.                                                                            |
| *Actions → CI → Run workflow* (default — `release` unchecked) | `build` + SNAPSHOT publish (same as a push, useful to re-publish a specific branch on demand).                                    |
| *Actions → CI → Run workflow* with `release: true`            | `build` + `-Prelease publish githubRelease` — Nexus `maven-releases` + a GitHub Release `v<version>` with every module's `*.jar`. |

**Locally (ad-hoc / debugging only).**

```sh
# SNAPSHOT publish to Nexus (from any non-master branch → tagged with branch).
./gradlew publish

# Release publish to Nexus `maven-releases`.
./gradlew -Prelease publish

# Full release: Nexus + GitHub Release + mavenLocal.
./gradlew -Prelease publish publishToMavenLocal githubRelease
```

The `githubRelease` task requires `-Prelease` (SNAPSHOTs never create a
GitHub Release — per workspace policy they live only in Nexus), needs the
`gh` CLI on `PATH` authenticated with `gh auth login` (or `GH_TOKEN` set,
as the Actions workflow does), and runs `gh release create v<version>`
attaching every module's `build/libs/*.jar`. Tag handling is delegated to
`gh` — the tag is created on the current `HEAD` if missing, so verify you
are on the right commit before invoking.

## Running tests across the workspace

- `./gradlew build` — full build across all three modules; runs Java tests
  and (inside `:workato-agent-extension-demo`) the Ruby suites.
- `./gradlew test` — runs every module's JUnit suite plus the demo's Ruby
  RSpec suites.
- `./gradlew :<module>:test` — one module only.
- `./gradlew :<module>:build` — one module only, fully.

Per-module test commands and conventions live in each module's `CLAUDE.md`.
