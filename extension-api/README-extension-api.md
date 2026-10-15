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

> **Restart required to add, remove, or load code for extensions.** Dropping a
> new JAR into `lib_ext/` or adding a new entry under `extensions:` does not
> take effect until the agent is restarted; routes and classloaders are fixed
> for the process lifetime.
>
> **Reloading `config.yml` updates values only.** For an already-registered
> extension, edits to its YAML values are picked up on the next request via
> `@Value("${...}")`. The map returned by
> `AgentExtensionContextV1.getProfileConfiguration()` is a snapshot captured at
> startup and does **not** change — prefer `@Value` for keys that may be edited
> at runtime.

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

| Method                      | What you get                                                                                                                                                                       |
|-----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `getProfileName()`          | The YAML key your extension is declared under (`my-extension` in the example above). Stable for the lifetime of the agent.                                                         |
| `getProfileConfiguration()` | Your extension's YAML section as a map, including the `controller` / `controllerClass` key alongside your custom properties. Snapshot — does not change across agent YAML reloads. |

> **Each extension sees only its own profile.** There is no API for discovering
> other extensions' configuration. (Note that this does not provide full
> isolation on its own: an extension controller still shares a Spring parent
> context with agent internals and can reach other beans by autowiring.)

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

### `ExtensionEndpoint` interface — not supported

The deprecated `com.workato.agent.config.ExtensionEndpoint` interface has no
bean registered against it — `@Autowired ExtensionEndpoint` fails at startup
with `NoSuchBeanDefinitionException`. Use `@Autowired AgentExtensionApiV1`
instead.

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

- `AgentExtensionApiV1` and `AgentExtensionContextV1` are **consumer-only** —
  customer code calls their methods but does not implement them (the
  implementation is supplied by the agent). Methods may be added to these
  interfaces without breaking customer compile or runtime, as long as the
  customer does not provide their own implementation.
- `AgentConfigurationV1` extends `Map<String, Object>`; additional typed
  accessors may be added here in future iterations, source- and binary-compatible
  with existing calls.
- Incompatible changes will introduce a parallel `V2` family and keep `V1`
  running until a documented removal window.

If you write a hand-rolled implementation of `AgentExtensionApiV1` or
`AgentExtensionContextV1` for testing, prefer the harness in `extension-test`
over implementing the interfaces directly — the harness is kept in sync as
the interfaces evolve.
