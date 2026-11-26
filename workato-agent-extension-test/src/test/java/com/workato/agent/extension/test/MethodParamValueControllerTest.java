/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.MethodParamValueController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: a controller whose handler method receives {@code @Value}-annotated parameters —
 * one from the profile side, one from the connection side, one with a default — and pins that
 * Spring MVC's method-argument resolver reaches both PropertySources the harness wires up.
 *
 * <p>Complements {@link ConnectionValueControllerTest}, which pins the field-injection path.
 */
class MethodParamValueControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
            .controller(MethodParamValueController.class)
            .property("greeting", "hello")
            .connection("api.token", "secret-abc");

    @Test
    void valueOnMethodParametersResolvesAgainstBothSources() throws Exception {
        // The handler's three @Value-annotated params push it past the Ref0/Ref1 overloads the
        // harness exposes on request(...), so use the string URL form instead.
        MvcResult res = perform(agent.mockMvc(),
                MockMvcRequestBuilders.get(agent.urlFor("/probe"))
                        .accept(MediaType.APPLICATION_JSON));

        assertThat(status(res)).isEqualTo(200);
        assertThat(jsonMap(res))
                .containsEntry("greeting", "hello")
                .containsEntry("apiToken", "secret-abc")
                .containsEntry("timeoutMs", 3000);  // falls back to the default on the @Value
    }
}
