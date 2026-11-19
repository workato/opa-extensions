/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
/**
 * Public API provided by the agent to customer-developed extensions.
 *
 * <p>Types in this package form the single contract customer extension JARs
 * (deployed into {@code lib_ext/}) compile against. The module is intentionally
 * free of dependencies on other agent modules so that extension JARs stay thin
 * and agent internals can evolve without breaking the customer compile.
 *
 * <p>The implementation that provides this API to extensions lives in
 * {@code com.workato.agent.extension} inside {@code agent-core}.
 */
package com.workato.agent.extension.api;
