/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.PlainFieldOnlyProfileController;
import com.workato.agent.extension.test.testext.PlainFieldReferencesConnectionKeyController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the visibility rule for plain {@code @Value} field injection:
 * <ul>
 *   <li>A profile-sourced key is injected successfully.</li>
 *   <li>A connection-sourced key is <em>not</em> visible: bean creation fails with an
 *       unresolvable-placeholder error. Extensions that want late / lazy visibility into the
 *       connection payload must declare their field as {@code Supplier<T>} (see
 *       {@link SupplierValueControllerTest}) or receive it as a handler-method parameter (see
 *       {@link MethodParamValueControllerTest}).</li>
 * </ul>
 */
class PlainFieldValueVisibilityTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness profileOnly = AgentExtensionHarness.extension("my-profile")
            .controller(PlainFieldOnlyProfileController.class)
            .property("greeting", "hello");

    @Test
    void plainFieldResolvesProfileKey() throws Exception {
        MvcResult res = perform(profileOnly.mockMvc(),
                MockMvcRequestBuilders.get(profileOnly.urlFor("/probe"))
                        .accept(MediaType.APPLICATION_JSON));

        assertThat(status(res)).isEqualTo(200);
        assertThat(jsonMap(res)).containsEntry("greeting", "hello");
    }

    @Test
    void plainFieldReferringToConnectionOnlyKeyFailsBeanCreation() {
        AgentExtensionHarness fails = AgentExtensionHarness.extension("my-profile")
                .controller(PlainFieldReferencesConnectionKeyController.class)
                .connection("api.token", "secret-abc");

        assertThatThrownBy(fails::start)
                .hasStackTraceContaining("api.token");
    }
}
