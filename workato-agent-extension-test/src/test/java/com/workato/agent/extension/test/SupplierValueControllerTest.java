/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.SupplierValueController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that {@code @Value} on {@code Supplier<T>} fields and handler-method parameters resolves
 * against both the profile and the connection PropertySources — the same two sources that feed
 * plain {@code @Value}. Supplier shape matters for callers that want late / per-call resolution
 * instead of the one-shot bean-init snapshot plain fields capture.
 */
class SupplierValueControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
            .controller(SupplierValueController.class)
            .property("greeting", "hello")
            .connection("api.token", "secret-abc");

    @Test
    void supplierValueResolvesBothProfileAndConnectionKeys() throws Exception {
        MvcResult res = perform(agent.mockMvc(),
                MockMvcRequestBuilders.get(agent.urlFor("/probe"))
                        .accept(MediaType.APPLICATION_JSON));

        assertThat(status(res)).isEqualTo(200);
        assertThat(jsonMap(res))
                .containsEntry("greeting", "hello")
                .containsEntry("apiToken", "secret-abc");
    }
}
