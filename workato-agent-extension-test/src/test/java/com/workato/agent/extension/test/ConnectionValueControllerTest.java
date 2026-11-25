/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.ConnectionValueController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: exercises a controller that pulls values from both the profile and the connection
 * via {@code @Value}, plus reads {@code contentHash()} through {@code getConnectionProperties()}.
 * The pass criterion is that {@code api.token} (connection-sourced) is injected into the Spring
 * bean just like {@code greeting} (profile-sourced).
 */
class ConnectionValueControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
            .controller(ConnectionValueController.class)
            .property("greeting", "hello")
            .connection("api.token", "secret-abc");

    @Test
    void valueInjectionReachesBothProfileAndConnectionSources() throws Exception {
        MvcResult res = perform(agent.mockMvc(),
                agent.request(ConnectionValueController::probe).accept(MediaType.APPLICATION_JSON));

        assertThat(status(res)).isEqualTo(200);
        assertThat(jsonMap(res))
                .containsEntry("greeting", "hello")
                .containsEntry("apiToken", "secret-abc")
                .containsEntry("connectionHash", agent.connectionProperties().contentHash());
    }
}
