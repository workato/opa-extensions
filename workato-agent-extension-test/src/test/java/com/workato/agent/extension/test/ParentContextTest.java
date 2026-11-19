/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import com.workato.agent.extension.api.AgentExtensionContextV1;
import com.workato.agent.extension.api.AgentExtensionV1;
import com.workato.agent.extension.api.AgentToolsV1;
import com.workato.agent.extension.api.AgentWebhookV1;
import com.workato.agent.extension.test.testext.TranslatingController;
import com.workato.agent.extension.test.testext.Translator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates {@code .parentContext(ctx)} — the harness's child context pulls autowire targets
 * from any {@link org.springframework.context.ApplicationContext} the test already has. Here the
 * {@link Translator} is declared in a parent built by the test; no {@code .bean(...)} /
 * {@code .configuration(...)} wiring is needed inside the harness itself.
 *
 * <p>In a real test with {@code @ExtendWith(SpringExtension.class)} /
 * {@code @ContextConfiguration(...)} / {@code @SpringBootTest}, the parent is simply the
 * {@code @Autowired ApplicationContext} the test already receives.
 */
class ParentContextTest extends AbstractExtensionTest {

    AnnotationConfigApplicationContext parent;
    AgentExtensionHarness agent;

    @BeforeEach
    void setup() {
        parent = new AnnotationConfigApplicationContext();
        parent.registerBean("translator", Translator.class);
        parent.refresh();

        agent = AgentExtensionHarness.extension("tx")
                .parentContext(parent)               // every bean in `parent` is autowire-visible
                .yaml("extensions/translator.yml")   // controller class declared in YAML
                .start();
    }

    @AfterEach
    void tearDown() {
        agent.stop();
        parent.close();
    }

    @Test
    void controllerAutowiresBeanFromParentContext() throws Exception {
        MvcResult res = perform(agent.mockMvc(),
                agent.request(TranslatingController::translate)
                        .contentType(MediaType.TEXT_PLAIN).content("inherited"));

        assertThat(status(res)).isEqualTo(200);
        assertThat(body(res)).isEqualTo("INHERITED");
    }

    /**
     * Regression guard for CLAUDE.md §8: the harness-registered {@code "agentExtension"} bean must
     * shadow any parent-context bean of the same name. If the parent's bean ever leaked into the
     * child, customer controllers would autowire a stub they did not configure.
     */
    @Test
    void harnessAgentExtensionShadowsParentAgentExtension() {
        AnnotationConfigApplicationContext parentWithConflict = new AnnotationConfigApplicationContext();
        AgentExtensionV1 parentStub = new AgentExtensionV1() {
            @Override
            public AgentExtensionContextV1 getContext() {
                throw new AssertionError("parent bean must not be reached");
            }

            @Override
            public AgentWebhookV1 webhook() {
                throw new AssertionError("parent bean must not be reached");
            }

            @Override
            public AgentToolsV1 getTools() {
                throw new AssertionError("parent bean must not be reached");
            }
        };
        parentWithConflict.getBeanFactory().registerSingleton("agentExtension", parentStub);
        parentWithConflict.refresh();

        AgentExtensionHarness childAgent = AgentExtensionHarness.extension("tx")
                .parentContext(parentWithConflict)
                .yaml("extensions/translator.yml")
                .bean("translator", new Translator())
                .start();
        try {
            AgentExtensionV1 resolved = childAgent.agentExtension();
            assertThat(resolved)
                    .as("child context must shadow parent bean of the same name")
                    .isNotSameAs(parentStub);
            assertThat(resolved).isInstanceOf(FakeAgentExtension.class);
        } finally {
            childAgent.stop();
            parentWithConflict.close();
        }
    }
}
