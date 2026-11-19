/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.api;

import java.util.Map;

/**
 * Per-endpoint context handed to a customer extension controller through
 * {@link AgentExtensionV1#getContext()}.
 *
 * <p>Replaces the legacy {@code com.workato.agent.config.DefaultExtensionEndpoint}
 * record (bean name {@code "workatoEndpoint"}); a different instance of
 * {@code AgentExtensionContextV1} exists in each extension's isolated Spring
 * context, carrying only that endpoint's identity and configuration.
 *
 * <p><b>Security note — deliberate omission of cross-extension visibility.</b>
 * Earlier drafts of this API exposed a {@code getAllConfigurations()} method
 * returning every loaded extension's YAML section. It was removed before
 * release: in a multi-tenant deployment where extensions come from different
 * third parties, one compromised extension could use it to exfiltrate every
 * other extension's YAML configuration — including secrets, credentials, and
 * endpoint URLs that administrators consider private to their respective
 * profiles. Each context now sees only its own profile.
 *
 * <p>This fix narrows one specific attack vector but does <em>not</em> provide
 * full isolation on its own. An extension JAR running inside the agent has the
 * extension context's parent set to the main agent {@code ApplicationContext},
 * which means it can still obtain any agent bean via {@code @Autowired} or the
 * {@code ApplicationContext} itself and reach configuration through other
 * paths (e.g., reading the {@code ServerConfig} bean directly). True
 * cross-extension isolation requires restricting the extension context's
 * visibility to the API beans only — out of scope for this release and
 * tracked separately.
 */
public interface AgentExtensionContextV1 {

    /**
     * The name under which this extension is declared in {@code config.yml}:
     * the key of its section under {@code extensions:}. Stable for the
     * lifetime of the agent process.
     */
    String getProfileName();

    /**
     * The YAML section belonging to this extension, exposed as a map-shaped
     * view. The {@code controller} / {@code controllerClass} key is included
     * verbatim alongside custom properties — the whole section is passed through.
     *
     * <p>The returned instance is an unmodifiable view: mutating operations
     * throw {@link UnsupportedOperationException}. Treat it as read-only.
     */
    AgentConfiguration getProfileConfiguration();

    /**
     * Map-shaped view of an extension endpoint's YAML section — the configuration
     * belonging to the extension, exposed to its controller.
     *
     * <p>Nested inside {@link AgentExtensionContextV1} so a single import brings in both the
     * context and its configuration type. Extends {@link Map} with {@code String} keys and
     * {@code Object} values: the configuration <em>is</em> a map, so customers can iterate,
     * look up by key, and stream over its entries using the standard JDK API. Additional typed
     * accessors may be added to this interface in future iterations; because they are added to
     * an interface that already carries the full {@link Map} contract, such additions are
     * source- and binary-compatible.
     *
     * <p>The implementation returned by {@link #getProfileConfiguration()} is an unmodifiable
     * view backed by a defensive copy — any mutating operation ({@code put}, {@code remove},
     * {@code clear}, …) throws {@link UnsupportedOperationException}, and the agent's internal
     * state is unaffected.
     */
    interface AgentConfiguration extends Map<String, Object> {
    }
}
