/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.EchoController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP happy-path: GET endpoint, {@code @Value} injection, {@code @Autowired AgentExtensionV1}.
 * Pure controller-side behaviour — the fake API's own contract is pinned separately in
 * {@link AgentExtensionContextV1Test}.
 */
class EchoControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
            .yaml("extensions/echo.yml");  // controller class is declared in the YAML

    @Test
    void endpointAnswersAtProductionLikeUrl() throws Exception {
        MvcResult res = perform(agent.mockMvc(),
                agent.request(EchoController::echo).accept(MediaType.APPLICATION_JSON));

        assertThat(status(res)).isEqualTo(200);
        assertThat(jsonMap(res))
                .containsEntry("greeting", "hello")
                .containsEntry("profile", "my-profile");
    }
}
