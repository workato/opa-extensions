/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.api;

/**
 * Version 1 of the API that the agent provides to customer-developed extensions.
 *
 * <p>Customer {@code @RestController}s loaded from {@code lib_ext/} may
 * {@code @Autowired} a bean of this type to obtain the API surface.
 *
 * <p>A separate instance exists in every extension's isolated Spring context,
 * so {@link #getContext()} always reflects the current endpoint — the one
 * serving the request. The implementation is supplied by {@code agent-core}
 * ({@code com.workato.agent.extension.AgentExtensionV1Impl}) and registered
 * by {@code EndpointBeanRegistrar} per endpoint.
 */
public interface AgentExtensionV1 {

    /**
     * Context of the extension endpoint that owns this API instance: its
     * profile name and the YAML configuration it was declared with.
     */
    AgentExtensionContextV1 getContext();

    /**
     * Thin wrapper over outbound {@code POST} with retry and exponential backoff, for pushing
     * events to a recipe webhook URL or waking a pull-mode recipe trigger. The returned instance
     * is scoped to this extension's profile — the retry policy comes from the profile's YAML
     * {@code webhook:} section, falling back to {@link AgentWebhookV1.RetryPolicy#DEFAULTS}
     * when the section is absent.
     *
     * <p>Thread-safe; extensions can cache the reference and call it from any thread.
     */
    AgentWebhookV1 webhook();

    /**
     * Agent-provided utilities (currently a shared {@link tools.jackson.databind.ObjectMapper})
     * that the extension should use instead of constructing its own. Keeps Jackson out of the
     * extension's shaded jar and aligns extension JSON handling with the agent's configuration.
     *
     * <p>Thread-safe; extensions can cache the reference.
     */
    AgentToolsV1 getTools();
}
