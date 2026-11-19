/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.TranslatorConfig;
import com.workato.agent.extension.test.testext.UpperCasingController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller whose collaborator comes from a @Configuration class, not a direct bean.
 */
class UpperCasingControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("upper")
            .yaml("extensions/upper.yml")        // controller class declared in YAML
            .configuration(TranslatorConfig.class);

    @Test
    void beansFromConfigurationClassAreVisible() throws Exception {
        MvcResult res = perform(agent.mockMvc(),
                agent.request(UpperCasingController::upper, "world"));

        assertThat(status(res)).isEqualTo(200);
        assertThat(body(res)).isEqualTo("WORLD");
    }
}
