/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extension that autowires a {@link Translator} supplied through a @Configuration class.
 */
@RestController
public class UpperCasingController {

    @Autowired
    Translator translator;

    @GetMapping(value = "/upper/{word}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String upper(@PathVariable("word") String word) {
        return translator.translate(word);
    }
}
