OPA extension SDK allows customers, external developers/partners to connect to legacy on-prem systems using Java within
the Workato Agent infrastructure.

## Configuration

Extension mechanism is enabled by adding the `extensions` section to the config file, `conf/config.yml`:

```yml
    server:
      classpath: C:\\Program Files\\Workato Agent\\ext
    extensions:
      security:
        controllerClass: com.mycompany.onprem.SecurityExtension
        secret: HA63A3043AMMMM
```

Individual endpoints are defined inside `extensions`, where every key serves as _endpoint name_. Every _endpoint name_
has to be valid identifier, has to be URL-friendly and cannot contain any special characters (like whitespace, etc.).

Classpath defines a set of folders/JAR files containing compiled extensions.

## Using extension endpoints

Every endpoint is hosted inside the REST-compliant URL: `/ext/<endpointName>/<resource>` where `<endpointName>`
corresponds to key defined in the configuration file and `<resource>` is an specific REST resource within the endpoint.

Endpoint request handling logic is defined by Spring REST controller class, provided as `controllerClass` property. This
controller class is registered inside an endpoint-specific Spring `WebApplicationContext` and allows retrieving
configuration properties from the `config.yml` file.

## Endpoint controller sample

Full code can be
found [here](https://github.com/workato/opa-extensions/blob/master/src/main/java/com/mycompany/onprem/SecurityExtension.java).

```java
package com.mycompany.onprem;

// import rest of the packages here:
@Controller
public class SecurityExtension {

    @Inject
    private Environment env;

    @RequestMapping(method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> computeDigest(@RequestBody Map<String, Object> body) throws Exception {
        Charset encoding = Charset.forName("UTF-8");
        String payload = (String) body.get("payload");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(env.getProperty("secret").getBytes(encoding));
        byte[] result = digest.digest(payload.getBytes(encoding));
        return Collections.singletonMap("signature", Hex.encodeHexString(result));
    }
}
```

Given the configuration:

```yml
    extensions:
      security:
        controllerClass: com.mycompany.onprem.SecurityExtension
        secret: HA63A3043AMMMM 
```

## Project setup: Extension API and test-harness JARs

Both the compile and test classpaths depend on two JARs shipped with your
Workato agent distribution. Before the project will build, copy them from the
distribution into the project's `extension-api/` directory:

| File                           | Source                                     | Gradle scope         | Purpose                                                      |
|--------------------------------|--------------------------------------------|----------------------|--------------------------------------------------------------|
| `extension-api-<version>.jar`  | agent distribution (same version as agent) | `implementation`     | Production API your extension compiles against.              |
| `extension-test-<version>.jar` | agent distribution (same version as agent) | `testImplementation` | Mock/harness used by tests that run without a running agent. |

Both JARs **must come from the same agent distribution** — the version suffix
has to match. Mixing versions is a startup bug waiting to happen; the build
rejects it before you get there.

Resulting layout:

```
extension-api/
├── extension-api-<version>.jar     ← you copy this
├── extension-test-<version>.jar    ← you copy this
├── README-extension-api.md         ← generated on each build
└── README-extension-test.md        ← generated on each build
```

### What the build verifies

Every compile runs the `validateExtensionJars` task, which fails the build with
a copy-them-from-the-agent hint if any of the following is true:

- the `extension-api/` directory is missing;
- either JAR is absent;
- more than one version of either JAR is present;
- the two JARs come from different versions.

### Extracted documentation

The build also runs `extractExtensionReadmes`, which pulls `README.md` out of
each JAR into `extension-api/`:

- **[`extension-api/README-extension-api.md`](extension-api/README-extension-api.md)**
  — the authoritative reference for writing an extension (API surface,
  configuration access, lifecycle, injection, migration from legacy types).
  Read this before writing production code.
- **[`extension-api/README-extension-test.md`](extension-api/README-extension-test.md)**
  — the authoritative reference for testing an extension with the mocked API
  (test harness setup, `MockMvc` usage, property/YAML wiring, troubleshooting).
  Read this before writing tests.

Both files are regenerated from the JARs on every build, so they always match
the version currently on the classpath. Prefer them over any snippets in this
README or elsewhere.

> This repository already contains a wired-up `build.gradle` that applies both
> JARs to the right scopes. If you start a project from scratch, use the
> `dependencies { ... }`, `validateExtensionJars` and `extractExtensionReadmes`
> blocks in this project's `build.gradle` as the reference.

## Building extension

Steps to build an extension:

1. Install the latest [Java 21 SDK](https://adoptium.net/).
2. Copy the two JARs from the agent distribution into `extension-api/`
   (see the preceding section).
3. Run `./gradlew jar` — this also triggers `validateExtensionJars` and
   `extractExtensionReadmes`.
4. The output is in `build/libs`.

Useful standalone Gradle tasks:

- `./gradlew validateExtensionJars` — verify the two JARs without compiling.
- `./gradlew extractExtensionReadmes` — refresh the extracted README files.
- `./gradlew test` — run tests against the mocked API (uses
  `extension-test-<version>.jar`).

## Installing the extension to OPA

1. Add a new directory called `ext` under Workato agent install directory.
2. Copy the extension JAR file to `ext` directory.
3. Update the `config/config.yml` to add the `ext` file to class path.

```yml
    server:
      classpath: C:\\Program Files\\Workato Agent\\ext
```

4. Update the `config/config.yml` to configure the new extension.

```yml
    extensions:
      security:
        controllerClass: com.mycompany.onprem.SecurityExtension
        secret: HA63A3043AMMMM
```

## Using the extension in Recipe

In order to use the extension in a recipe, we need a custom adapter in Workato. Sample adapter for the extension
can be
found [here](https://github.com/workato/connector_sdk/blob/master/custom_connectors/basic_auth/onprem_security.rb).

```ruby
{
  title: 'On-prem security',
  secure_tunnel: true,

  connection: {
    fields: [{ name: 'profile', hint: 'On-prem security connection profile' }],
    authorization: { type: 'none'}
  },

  test: ->(connection) {
    post("http://localhost/ext/#{connection['profile']}/computeDigest", { payload: 'test' }).headers('X-Workato-Connector': 'enforce')
  },

  actions: {
    sha256_digest: {

      title: 'Create SHA-256 digest',
      description: 'Create <span class="provider">SHA-256</span> digest',

      input_fields: ->(_) { [{ name: 'payload' }] },
      output_fields: ->(_) { [{name: 'signature'}] },

      execute: ->(connection, input) {
        post("http://localhost/ext/#{connection['profile']}/computeDigest", input).headers('X-Workato-Connector': 'enforce')
      }
    }
  }
}
```
