/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.DefaultingController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Value("${key:default}")} fallback kicks in when the key is absent from YAML.
 */
class DefaultingControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("defaults")
            .yaml("extensions/defaults.yml");   // controller class declared in YAML

    @Test
    void defaultIsUsedWhenPropertyAbsent() throws Exception {
        MvcResult res = perform(agent.mockMvc(), agent.request(DefaultingController::v));

        assertThat(status(res)).isEqualTo(200);
        assertThat(body(res)).isEqualTo("fallback");
    }
}
