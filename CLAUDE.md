# CLAUDE.md — workspace root

Workspace-level orientation for Claude agents. Per-module guidance lives in
each module's own `CLAUDE.md` — read the one that matches what you are
touching.

## Workspace

OPA (On-Premise Agent) extension workspace. Three Gradle modules, all compiled
from source and built together:

| Module                              | Purpose                                                                                                                                                                                                                                                                                                                                             | Per-module guidance                                                                                                                                                                     |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`workato-agent-extension-api/`**  | Public Extension API that customer extensions compile against. Published to GitHub Packages as a regular library (jar + sources + javadoc); the agent (OPA) consumes it from there and provides the interfaces at runtime.                                                                                                                                    | [`workato-agent-extension-api/CLAUDE.md`](workato-agent-extension-api/CLAUDE.md) + customer-facing [`workato-agent-extension-api/README.md`](workato-agent-extension-api/README.md)     |
| **`workato-agent-extension-test/`** | Test-harness library. Depends on `-api`. Customers put it on their test classpath to drive their extension through a Spring `MockMvc` runtime without launching the real agent. Published to GitHub Packages as a regular library.                                                                                                                            | [`workato-agent-extension-test/CLAUDE.md`](workato-agent-extension-test/CLAUDE.md) + customer-facing [`workato-agent-extension-test/README.md`](workato-agent-extension-test/README.md) |
| **`workato-agent-extension-demo/`** | Sample extensions (`security`, `ntlm`, `timer`) plus their JUnit tests and Workato custom connectors (Ruby). Published to GitHub Packages with three classifiers: shaded fat jar, `-sources` (Java + Ruby connectors under `ruby/`), and `-javadoc`. The primary role is end-to-end exercise of the two artifacts above and a reference customers clone from. | [`workato-agent-extension-demo/CLAUDE.md`](workato-agent-extension-demo/CLAUDE.md) + customer-facing [`workato-agent-extension-demo/README.md`](workato-agent-extension-demo/README.md) |

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

The base version lives in `gradle.properties` (`version=0.1.0`) and is
consumed as `rootProject.version` by Gradle directly. The `version.suffix`
property is the lever that flips the build between release and SNAPSHOT —
root `build.gradle` reads it (CLI flag, `~/.gradle/gradle.properties`, or
`ORG_GRADLE_PROJECT_version.suffix` env var, in Gradle's standard precedence)
and appends it to the base version:

| How `version.suffix` is supplied      | Effective version          | SNAPSHOT semantics applied by consumers? |
|---------------------------------------|----------------------------|------------------------------------------|
| `-Pversion.suffix=SNAPSHOT`           | `0.1.0-SNAPSHOT`           | yes — mutable, re-resolved on each build |
| `-Pversion.suffix=feature-x-SNAPSHOT` | `0.1.0-feature-x-SNAPSHOT` | yes                                      |
| `-Pversion.suffix=` (explicit empty)  | `0.1.0`                    | no — immutable release                   |
| **Not passed** — local auto-detect    | see below                  | depends on branch                        |

All versions land in the same GitHub Packages Maven repo
(`https://maven.pkg.github.com/workato/opa-extensions`) — Maven's SNAPSHOT
semantics are driven by the version string, not by two separate upload
endpoints.

**Local auto-detect** runs when `version.suffix` is not passed at all
(property absent). It executes `git rev-parse --abbrev-ref HEAD` in the
workspace root and picks:

| git result                            | Resolved suffix               | Effective version          |
|---------------------------------------|-------------------------------|----------------------------|
| `master` / `main`                     | `SNAPSHOT`                    | `0.1.0-SNAPSHOT`           |
| any other branch (e.g. `feature/x`)   | `<sanitized-branch>-SNAPSHOT` | `0.1.0-feature-x-SNAPSHOT` |
| detached `HEAD`, git failure, timeout | `SNAPSHOT` (safe default)     | `0.1.0-SNAPSHOT`           |

Sanitisation replaces anything outside `[A-Za-z0-9._-]` with `-` and
collapses consecutive dashes — Maven versions cannot contain `/` or spaces.
The sanitised branch is directly recoverable from the published version
string, so any SNAPSHOT in GitHub Packages can be traced back to the branch
it came from.

**Who passes what.** CI always passes `-Pversion.suffix=<value>` explicitly
(computed in `ci.yml` from `GITHUB_REF_NAME` + the `release` dispatch input),
so the auto-detect path only fires on developer laptops. The auto-detect
gives `./gradlew publishToMavenLocal` branch-aware SNAPSHOT behaviour out of
the box, without any flags. The safe default on detached HEAD / git failure
is `SNAPSHOT` (never `0.1.0` — so a glitchy local environment can never
silently release).

### Publication layout

| Module                         | Publication content                                                                                                                                           | POM dependencies                                                      |
|--------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| `workato-agent-extension-api`  | main jar + `-sources` + `-javadoc` (from `components.java`); README.md is bundled inside both the main jar and the javadoc jar                                | `platform(workato-agent-bom)` + Jackson `compileOnly`                 |
| `workato-agent-extension-test` | main jar + `-sources` + `-javadoc` (from `components.java`); README.md is bundled inside both the main jar and the javadoc jar                                | `platform(workato-agent-bom)` + api-module GAV + Spring/Jackson/JUnit |
| `workato-agent-extension-demo` | shaded fat jar + `-sources` (Java + Ruby connectors under `ruby/` + per-extension READMEs) + `-javadoc` (with top-level README); fat jar bundles runtime deps | none — the fat jar has everything bundled                             |

All three publications land in GitHub Packages
(`https://maven.pkg.github.com/workato/opa-extensions`) when
`./gradlew publish` runs. `publishToMavenLocal` installs them under
`~/.m2/repository/com/workato/agent/`.

### Consuming the published artifacts

GitHub Packages Maven **requires authentication even for reading public
packages** (unlike npm or container packages served from the same product).
Any consumer — another Workato repo's CI, an external integrator, a
developer laptop — must configure a GitHub personal access token with the
`read:packages` scope and point Gradle/Maven at the repo:

```groovy
// settings.gradle or build.gradle
repositories {
    maven {
        url = uri('https://maven.pkg.github.com/workato/opa-extensions')
        credentials {
            username = providers.gradleProperty('githubActor').orNull
            password = providers.gradleProperty('githubToken').orNull
        }
    }
    mavenCentral()
}
```

Then in `~/.gradle/gradle.properties`:

```properties
githubActor=<your-github-login>
githubToken=<pat-with-read:packages>
```

Missing credentials surface as a 401 on dependency resolution.

**Published POMs are self-contained.** The `workato-agent-bom` is a
build-time-internal artifact — it pins versions across subprojects at
compile time and is NOT published to any repository. To make our published
artifacts resolvable without the BOM, root `build.gradle` configures every
`MavenPublication` with three measures:

1. `versionMapping` — bakes the runtime-resolved version onto every
   `<dependency>` in the generated POM (instead of leaving deps without a
   `<version>` and relying on a BOM import to pin them).
2. `pom.withXml` — strips the `<dependencyManagement>` section that
   `platform(...)` would otherwise leave in the POM.
3. **Gradle Module Metadata (`*.module`) publication is disabled**
   (`tasks.withType(GenerateModuleMetadata).configureEach { enabled = false }`).
   Steps 1+2 only sanitise the POM; `.module` keeps the `api platform(...)`
   calls as a BOM constraint, and Gradle-native consumers prefer `.module`
   over POM when both exist — so without this step a Gradle consumer still
   tries to resolve `workato-agent-bom` and fails with a 404 from GitHub
   Packages. Trade-off: Gradle-native consumers lose variant-aware
   resolution, which for plain Java libraries almost never matters.

Result: every consumer — Maven or Gradle — resolves our artifacts straight
from `com.workato.agent:*:<version>` using the POM alone, without ever
touching `workato-agent-bom`.

### Credentials

**Primary path — GitHub Actions.** Publish does not happen from developer
laptops; it runs in `.github/workflows/ci.yml` as the `publish` job (gated
by `needs: build`, so a red test suite blocks every upload). The job uses
only the auto-provided workflow token — no repo-level secrets are required:

| GitHub Actions value | Injected as                                       | Used for                                               |
|----------------------|---------------------------------------------------|--------------------------------------------------------|
| `github.actor`       | `ORG_GRADLE_PROJECT_githubActor` → `githubActor`  | GitHub Packages basic-auth username                    |
| `secrets.GITHUB_TOKEN` | `ORG_GRADLE_PROJECT_githubToken` → `githubToken` | GitHub Packages basic-auth password + `gh` CLI session |

For the `publish` job's token to carry write access to the repo's Packages
feed the workflow declares `permissions: packages: write` (and
`contents: write` for the `githubRelease` task's `gh release create`). No
manual secret configuration is needed.

**Secondary path — local override.** A maintainer running `./gradlew publish`
locally supplies credentials via the usual Gradle property lookup: CLI
flags (`-PgithubActor=... -PgithubToken=...`), `~/.gradle/gradle.properties`,
or the equivalent `ORG_GRADLE_PROJECT_*` env vars. The token needs
`write:packages` scope. Missing credentials only break `publish`; local
builds, `publishToMavenLocal`, and SNAPSHOT dev loops all work without
them.

### Release commands

**From GitHub Actions (normal path).** One workflow (`ci.yml`), two jobs
(`build` → `publish`); `publish` runs only if `build` succeeds, so a failed
test suite means no GitHub Packages upload and no GitHub Release:

| Trigger                                                       | Effect                                                                                                                                                                                  |
|---------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Pull request                                                  | `build` only — PRs never publish (reviewed via build status).                                                                                                                           |
| Push to any branch                                            | `build` + SNAPSHOT publish — CI step `Compute version suffix` derives `SNAPSHOT` (master/main) or `<branch>-SNAPSHOT` from `GITHUB_REF_NAME` and passes `-Pversion.suffix=…` to Gradle. |
| *Actions → CI → Run workflow* (default — `release` unchecked) | Same as a push (useful to re-publish a specific branch on demand).                                                                                                                      |
| *Actions → CI → Run workflow* with `release: true`            | `build` + `publish githubRelease -Pversion.suffix=` — GitHub Packages release upload + a GitHub Release `v<version>` with every module's `*.jar`.                                       |

**Locally (ad-hoc / debugging only).**

```sh
# SNAPSHOT publish to GitHub Packages — auto-detects the current branch via git.
./gradlew publish

# Pin the suffix explicitly (handy when the local git state is noisy).
./gradlew publish -Pversion.suffix=my-feature-SNAPSHOT

# Release publish — empty suffix is the release signal (version becomes 0.1.0).
./gradlew publish -Pversion.suffix=

# Full release: GitHub Packages + GitHub Release + mavenLocal.
./gradlew publish publishToMavenLocal githubRelease -Pversion.suffix=
```

The `githubRelease` task refuses to run when `version` ends in `-SNAPSHOT`
(SNAPSHOTs never create a GitHub Release — per workspace policy they live
only in GitHub Packages). It needs the `gh` CLI on `PATH` authenticated
with `gh auth login` (or `GH_TOKEN` set, as the Actions workflow does), and
runs `gh release create v<version> --generate-notes` attaching every
module's `build/libs/*.jar`. Tag handling is delegated to `gh` — the tag is
created on the current `HEAD` if missing, so verify you are on the right
commit before invoking.

## Running tests across the workspace

- `./gradlew build` — full build across all three modules; runs Java tests
  and (inside `:workato-agent-extension-demo`) the Ruby suites.
- `./gradlew test` — runs every module's JUnit suite plus the demo's Ruby
  RSpec suites.
- `./gradlew :<module>:test` — one module only.
- `./gradlew :<module>:build` — one module only, fully.

Per-module test commands and conventions live in each module's `CLAUDE.md`.
