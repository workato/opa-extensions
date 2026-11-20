/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.api;

import tools.jackson.databind.ObjectMapper;

/**
 * Version 1 bag of agent-provided utilities that customer extensions may need but should not
 * build themselves. Obtained from {@link AgentExtensionV1#getTools()}.
 *
 * <p>Everything exposed here is a reference to an instance the hosting agent already owns — the
 * extension-api module never constructs these itself. Consequences:
 * <ul>
 *   <li>Returned instances follow the agent's configuration (registered Jackson modules,
 *       cache sizes, …). Extensions get production-identical behaviour without having to
 *       re-configure anything.</li>
 *   <li>Returned instances are shared across the whole agent process. They are documented as
 *       thread-safe by their respective libraries; extensions must not reconfigure them
 *       (e.g., {@code registerModule} would leak into every other extension). Call
 *       {@code ObjectMapper.copy()} first if you need a customised copy.</li>
 *   <li>The agent's runtime always provides these libraries, so {@code extension-api} keeps
 *       them as {@code compileOnly} dependencies — extension JARs must not shade them.</li>
 * </ul>
 */
public interface AgentToolsV1 {

    /**
     * The agent's shared {@link ObjectMapper}, configured with the same modules and features the
     * agent uses to serialise and deserialise its own HTTP traffic. Thread-safe; cache or use
     * directly. Do not reconfigure in place — call {@code ObjectMapper.copy()} first if a
     * local customisation is needed.
     */
    ObjectMapper objectMapper();
}
