/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.DottedKeyController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dots in YAML keys are literal, not nesting separators — mirrors the agent's production
 * {@code EndpointPropertySource} semantics for extensions whose config uses dotted-key names.
 */
class DottedKeysTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("mail")
            .yaml("extensions/mail.yml");       // controller class declared in YAML

    @Test
    void dottedKeysResolveAsLiteral() throws Exception {
        MvcResult res = perform(agent.mockMvc(), agent.request(DottedKeyController::mail));

        assertThat(status(res)).isEqualTo(200);
        assertThat(jsonMap(res)).containsEntry("u", "alice").containsEntry("p", "s3cret");
    }
}
