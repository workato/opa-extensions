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
     * Current connection-scoped properties for this endpoint — the secrets and
     * settings carried in by the connection that triggered the request, as
     * distinct from the static profile configuration declared in
     * {@code config.yml}. Typical content: OAuth tokens, per-tenant base URLs,
     * credentials that must not live in a YAML file.
     *
     * <p>Like {@link #getProfileConfiguration()} the returned view is
     * unmodifiable; unlike it, connection content is logically tied to the
     * caller rather than to the endpoint, and a future revision may allow the
     * agent to refresh it between requests. The {@link ConnectionProperties#contentHash()}
     * accessor exposes a stable fingerprint callers can use as a cache key
     * (e.g., keyed on {@code "client-" + profile + "-" + hash}) to invalidate
     * precomputed state when the connection payload changes.
     *
     * <p>Every entry in the returned map is also resolvable via
     * {@code @Value("${key}")} on the controller, in addition to the profile
     * section. <b>Profile properties win on key collision:</b> if the same key
     * appears in both {@link #getProfileConfiguration()} and
     * {@link #getConnectionProperties()}, {@code @Value} resolves to the
     * profile's value and the agent emits an {@code ERROR}-level log entry
     * flagging the shadowed connection key. The connection map still reports
     * the connection-supplied value verbatim (it is a passthrough of what the
     * caller sent); only {@code @Value} resolution is overridden.
     */
    ConnectionProperties getConnectionProperties();

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

    /**
     * Map-shaped view of the connection-scoped properties attached to the current endpoint.
     * Extends {@link Map} for the same reason {@link AgentConfiguration} does — the payload
     * <em>is</em> a map, so customers consume it with the standard JDK API.
     *
     * <p>Like {@link AgentConfiguration}, the view is an unmodifiable snapshot: mutating ops
     * throw {@link UnsupportedOperationException}. Unlike it, this view adds a single extra
     * accessor, {@link #contentHash()}, intended as a cache key for state the extension
     * derives from the current connection payload.
     *
     * <h2>Hash contract</h2>
     * <ul>
     *   <li><b>Algorithm:</b> SHA-256 over the canonical form of the map entries, lowercase
     *       hex-encoded, 64 characters long.</li>
     *   <li><b>Canonical form:</b> entries sorted lexicographically by key; each entry rendered
     *       as {@code key=value\n} with values coerced through {@link String#valueOf(Object)}.
     *       Keeps the hash independent of the underlying map's iteration order.</li>
     *   <li><b>Stability:</b> identical contents produce identical hashes across JVM instances
     *       and across restarts, so the value is safe to persist or share across processes.</li>
     *   <li><b>Empty map:</b> hashes the empty canonical form — not a sentinel string, not null.</li>
     * </ul>
     */
    interface ConnectionProperties extends Map<String, Object> {

        /**
         * Lowercase hex SHA-256 fingerprint of the map contents. Stable across JVM instances
         * for identical content. See the class-level javadoc for the canonical form that
         * feeds the hash function.
         */
        String contentHash();
    }
}
