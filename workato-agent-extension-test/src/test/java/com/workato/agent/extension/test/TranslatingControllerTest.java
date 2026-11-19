/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.TranslatingController;
import com.workato.agent.extension.test.testext.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller with a @Autowired collaborator supplied via {@code .bean(name, instance)}.
 */
class TranslatingControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("tx")
            .yaml("extensions/translator.yml")   // controller class declared in YAML
            .bean("translator", new Translator());

    @Test
    void autowiredCollaboratorIsInjected() throws Exception {
        MvcResult res = perform(agent.mockMvc(),
                agent.request(TranslatingController::translate)
                        .contentType(MediaType.TEXT_PLAIN).content("hello"));

        assertThat(status(res)).isEqualTo(200);
        assertThat(body(res)).isEqualTo("HELLO");
    }
}
