/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class supplying a {@link Translator} bean for {@link UpperCasingController}.
 */
@Configuration
public class TranslatorConfig {

    @Bean
    Translator translator() {
        return new Translator();
    }
}
