/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

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
}
