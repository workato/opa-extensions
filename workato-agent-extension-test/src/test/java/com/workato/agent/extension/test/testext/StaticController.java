/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extension with a non-default constructor — only usable via {@code controller(Object)}.
 */
@RestController
public class StaticController {

    private final String canned;

    public StaticController(String canned) {
        this.canned = canned;
    }

    @GetMapping(value = "/canned", produces = MediaType.TEXT_PLAIN_VALUE)
    public String canned() {
        return canned;
    }
}
