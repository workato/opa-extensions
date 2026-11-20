/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.test.testext.EchoController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Builder-time validation guarantees — no Spring context is ever built.
 */
class BuilderValidationTest {

    @Test
    void blankProfileNameIsRejected() {
        assertThatThrownBy(() -> AgentExtensionHarness.extension("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startWithoutControllerIsRejected() {
        AgentExtensionHarness agent = AgentExtensionHarness.extension("x");
        assertThatThrownBy(agent::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void controllerSetTwiceIsRejected() {
        AgentExtensionHarness agent = AgentExtensionHarness.extension("x").controller(EchoController.class);
        assertThatThrownBy(() -> agent.controller(Object.class)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accessingMockMvcBeforeStartIsRejected() {
        AgentExtensionHarness agent = AgentExtensionHarness.extension("x").controller(EchoController.class);
        assertThatThrownBy(agent::mockMvc).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingYamlResourceIsRejected() {
        AgentExtensionHarness agent = AgentExtensionHarness.extension("x");
        assertThatThrownBy(() -> agent.yaml("extensions/does-not-exist.yml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void yamlMissingExtensionsSectionIsRejected() {
        AgentExtensionHarness agent = AgentExtensionHarness.extension("x");
        assertThatThrownBy(() -> agent.yaml("extensions/no-extensions-section.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("extensions:");
    }

    @Test
    void yamlMissingTargetProfileIsRejected() {
        AgentExtensionHarness agent = AgentExtensionHarness.extension("missing-profile");
        assertThatThrownBy(() -> agent.yaml("extensions/echo.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-profile");
    }

    @Test
    void yamlControllerClassIsPickedUpWithoutExplicitControllerCall() {
        // No .controller(...) — only .yaml(...). The YAML carries a valid controller class, so
        // start() must succeed and the Spring context must come up with that controller wired in.
        AgentExtensionHarness agent = AgentExtensionHarness.extension("my-profile")
                .yaml("extensions/echo.yml");
        try {
            assertThatCode(agent::start).doesNotThrowAnyException();
            assertThat(agent.webApplicationContext().getBean("controller").getClass().getName())
                    .isEqualTo("com.workato.agent.extension.test.testext.EchoController");
        } finally {
            agent.stop();
        }
    }

    @Test
    void yamlControllerClassNotFoundOnClasspathIsRejected() {
        AgentExtensionHarness agent = AgentExtensionHarness.extension("bad")
                .yaml("extensions/missing-controller-class.yml");
        assertThatThrownBy(agent::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NoSuchController");
    }
}
