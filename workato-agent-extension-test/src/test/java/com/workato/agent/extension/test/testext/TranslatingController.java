/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extension that autowires a {@link Translator} collaborator supplied via .bean(...).
 */
@RestController
public class TranslatingController {

    @Autowired
    Translator translator;

    @PostMapping(value = "/translate", consumes = MediaType.TEXT_PLAIN_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String translate(@RequestBody String body) {
        return translator.translate(body);
    }
}
