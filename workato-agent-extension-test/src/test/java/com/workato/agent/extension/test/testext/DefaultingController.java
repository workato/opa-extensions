/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extension exercising the {@code ${key:default}} fallback form of {@code @Value}.
 */
@RestController
public class DefaultingController {

    @Value("${not-declared:fallback}")
    String v;

    @GetMapping(value = "/v", produces = MediaType.TEXT_PLAIN_VALUE)
    public String v() {
        return v;
    }
}
