/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.StaticController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-built controller instance — demonstrates the {@code .controller(Object)} path for
 * controllers whose construction the customer wants to drive manually (e.g., non-default
 * constructor, Mockito {@code @InjectMocks}).
 */
class StaticControllerTest extends AbstractExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("canned")
            .controller(new StaticController("preassembled"))
            .yaml("extensions/canned.yml");

    @Test
    void preBuiltInstanceIsServed() throws Exception {
        MvcResult res = perform(agent.mockMvc(), agent.request(StaticController::canned));

        assertThat(status(res)).isEqualTo(200);
        assertThat(body(res)).isEqualTo("preassembled");
    }
}
