# Agent Extensions API — Customer Guide

> If you are reading this to **write an extension** for the Workato On-Premise
> Agent, you are in the right place. If you are reading this to **modify the
> API itself**, see `CLAUDE.md` in this directory instead.

Official product docs: <https://docs.workato.com/en/on-prem/agents/extension>

## What is an agent extension?

An extension is a Java JAR dropped into the agent's `lib_ext/` directory. It
contains a Spring `@RestController` that the agent mounts under
`/ext/{profile-name}/*`. Each extension gets its own isolated Spring context
inside the agent, so your code runs with its own beans and its own classloader
while still having read access to the agent's internal services through
autowiring.

The agent calls your controller when a Workato recipe sends a request to the
extension endpoint; your controller does whatever work it needs (talk to an
internal system, transform data, call a database) and returns a response.

## Quick start

### 1. Add the API as a provided-scope dependency

Gradle:

```gradle
dependencies {
    compileOnly("com.workato.build:extension-api")  // use the matching agent version
}
```

The API is **compile-only / provided** — do not shade it into your JAR. The
agent provides it at runtime; shading would cause classloader conflicts.

### 2. Write the controller

```java
package com.example.my_extension;

import com.workato.agent.extension.api.AgentExtensionApiV1;
import com.workato.agent.extension.api.AgentConfigurationV1;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/my-operation")
public class MyController {

    @Autowired
    private AgentExtensionApiV1 agent;

    @Value("${my-custom-property}")              // resolved from YAML; see §4
    private String myProperty;

    @PostMapping
    public Map<String, Object> handle(@RequestBody Map<String, Object> payload) {
        AgentConfigurationV1 cfg = agent.getContext().getProfileConfiguration();
        // ... business logic ...
        return Map.of("profile", agent.getContext().getProfileName(),
                "received", payload);
    }
}
```

### 3. Declare the extension in `config.yml`

```yaml
extensions:
  my-extension:
    controller: com.example.my_extension.MyController
    my-custom-property: value-visible-via-@Value
    other-prop: 42
```

### 4. Deploy

Build your JAR, copy it into `WORKATO_AGENT_HOME/lib_ext/`, restart the agent.
Requests from Workato arrive at `POST /ext/my-extension/my-operation`.

> **Classloader is fixed for the agent process lifetime.** Dropping a new JAR
> into `lib_ext/` does *not* reload — the agent must be restarted. YAML entries
> under `extensions:` *can* be reparsed on config reload, but no new classes
> are loaded without a restart.
>
> **Routes are mounted once at agent startup.** Dispatcher servlets for each
> `/ext/{name}/*` are installed on the first boot. A reload of `config.yml`
> reparses the `extensions:` section and updates the data visible via
> `AgentExtensionContextV1`, but **does not add new routes or tear down removed
> ones**. Concretely:
>
> - Adding a brand-new entry under `extensions:` and reloading → `/ext/{new}/*`
    > returns `404` until the agent is restarted.
> - Removing an existing entry and reloading → the route stays mounted; the
    > controller receives requests but `@Value("${...}")` lookups log a WARN
    > `"Endpoint ... not found, possibly removed"` and resolve to `null`.
> - Editing values under an existing entry → `@Value("${...}")` lookups pick
    > up the new values on the next request (via `EndpointPropertySource`),
    > but `getProfileConfiguration()` returns the snapshot captured at
    > startup and does *not* change. Prefer `@Value` for keys that may be
    > edited at runtime.
>
> If you need the full set of endpoints to change at runtime, restart the agent.

## API Reference

Every type lives in `com.workato.agent.extension.api`.

### `AgentExtensionApiV1` — the autowired bean

```java
public interface AgentExtensionApiV1 {
    AgentExtensionContextV1 getContext();
}
```

Autowire this. One instance exists per extension endpoint; it is scoped to
your extension's isolated Spring context. The bean name is `"agentExtensionApi"`,
but you should autowire by type, not by name.

### `AgentExtensionContextV1` — your endpoint's view of the agent

```java
public interface AgentExtensionContextV1 {
    String getProfileName();

    AgentConfigurationV1 getProfileConfiguration();
}
```

| Method                      | What you get                                                                                                                                                   |
|-----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `getProfileName()`          | The YAML key your extension is declared under (`my-extension` in the example above). Stable for the lifetime of the agent.                                     |
| `getProfileConfiguration()` | Your extension's YAML section as a map, including the `controller` key alongside your custom properties. Snapshot — does not change across agent YAML reloads. |

> **No cross-extension visibility by design.** An earlier draft exposed a
> `getAllConfigurations()` method returning every loaded extension's YAML
> section. It was removed before release: in a multi-tenant deployment where
> extensions come from different third parties, a compromised extension could
> have used it to exfiltrate every other extension's YAML — including secrets
> stored in those sections. Each extension now sees only its own profile.
>
> This narrows one attack vector but is **not full isolation** on its own.
> An extension JAR's Spring context has the agent's main `ApplicationContext` as its
> parent, which means the controller can still autowire any agent bean (e.g.,
> `ServerConfig`) and reach other extensions' configuration that way. True
> cross-extension isolation requires restricting the extension context's bean
> visibility to the API surface only — tracked separately; not shipped in this
> version. If you build a multi-tenant agent deployment with untrusted extension
> code, assume extensions can observe each other until that work lands.

### `AgentConfigurationV1` — map view of a YAML section

```java
public interface AgentConfigurationV1 extends Map<String, Object> {
}
```

Deliberately a bare `Map<String, Object>` extension. Use the standard JDK map
API: `cfg.get("key")`, `cfg.containsKey(...)`, iteration, streams. YAML nested
sections arrive as nested `Map` values; YAML lists arrive as `List` values.

> The instance you receive is an **unmodifiable view backed by a defensive copy**
> — any `put` / `remove` / `clear` / `putAll` throws
> `UnsupportedOperationException`, and the agent's internal state is unaffected.
> Treat it as read-only.

## Property injection with `@Value`

Any key under your extension's YAML section is injectable via Spring's
`@Value("${key}")`:

```yaml
extensions:
  my-extension:
    controller: com.example.my_extension.MyController
    api.endpoint: https://internal.example.com
    api.timeout-ms: 5000
```

```java
@Value("${api.endpoint}")
private String endpoint;

@Value("${api.timeout-ms:3000}")   // defaults supported
private int timeoutMs;
```

Dots in YAML keys are treated as literal characters, not nesting separators —
the lookup is a direct map `get()`. If you have a nested YAML section and want
to inject individual leaves, flatten the keys or inject the parent `Map`.

## Migrating from the legacy API

Older agent versions exposed two types in `com.workato.agent.config`. Both are
`@Deprecated(forRemoval = true)` and will be removed in a future major release;
every call to a deprecated method emits a `WARN` in the agent's log pointing at
this API.

### `DefaultExtensionEndpoint` (bean `"workatoEndpoint"`) — still works, now noisy

Record historically autowired as:

```java
@Autowired DefaultExtensionEndpoint workatoEndpoint;
```

Replace with:

```java
@Autowired AgentExtensionApiV1 agent;
```

Then migrate each call site:

| Legacy call                       | Replacement                                    |
|-----------------------------------|------------------------------------------------|
| `workatoEndpoint.name()`          | `agent.getContext().getProfileName()`          |
| `workatoEndpoint.configuration()` | `agent.getContext().getProfileConfiguration()` |

The returned `AgentConfigurationV1` is a `Map<String, Object>`, so if your old
code called `.get("key")` on the legacy `Configuration`, the new call is
identical. Typed accessors from the legacy `Configuration` (e.g., coerce to
`int`/`boolean`) are not currently on `AgentConfigurationV1` — do the coercion
yourself, or inject the leaf with `@Value`.

### `ExtensionEndpoint` interface — never actually worked

The deprecated `com.workato.agent.config.ExtensionEndpoint` interface was
documented historically, but **no bean of that type is ever registered** —
`@Autowired ExtensionEndpoint` fails at startup with
`NoSuchBeanDefinitionException`. If some tutorial told you to use it, that
tutorial was wrong. Replace with `@Autowired AgentExtensionApiV1` as above.

### `@Value` continues to work

The `@Value("${property}")` path is unchanged — resolution still goes through
the agent's internal `EndpointPropertySource` and does **not** emit any WARN.
If your extension only reads config properties via `@Value`, you have nothing
to migrate right now. Still, moving to `AgentExtensionApiV1` is recommended
before the legacy types are removed.

## Troubleshooting

| Symptom                                                                | Likely cause                                                                                                                                                                             |
|------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `NoSuchBeanDefinitionException` on `@Autowired ExtensionEndpoint`      | You are using the deprecated interface. Switch to `AgentExtensionApiV1`. See migration section.                                                                                          |
| WARN `Extension [X] called deprecated DefaultExtensionEndpoint#name()` | A caller is still on the legacy record. Follow the migration table — the WARN mentions the exact replacement.                                                                            |
| `/ext/{name}/{path}` returns 404                                       | Either the extension name is not declared under `extensions:` in `config.yml`, or the controller class could not be loaded (check the agent log for `Endpoint [{name}] failed to load`). |
| Changes to a redeployed JAR don't take effect                          | Classloader is fixed for the agent's lifetime. Restart the agent after replacing a JAR.                                                                                                  |
| Request parameter `@PathVariable id` is not bound                      | Build your JAR with the `-parameters` compiler flag, or declare the name explicitly: `@PathVariable("id")`.                                                                              |

## Versioning

The API is named `AgentExtensionApiV1` / `AgentExtensionContextV1` / `AgentConfigurationV1`.
Additive changes (new methods on these interfaces) will be done in-place and
are source- and binary-compatible when consumed via standard reflection and
Spring injection. Incompatible changes will introduce a parallel `V2` family
and keep `V1` running until a documented removal window.
