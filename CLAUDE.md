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

## Build

- `./gradlew build` — full build; triggers JAR validation and README
  extraction automatically.
- `./gradlew validateExtensionJars` — JAR presence/version check only.
- `./gradlew extractExtensionReadmes` — refresh the two README files.

Java 17, Gradle 7.4.2.
