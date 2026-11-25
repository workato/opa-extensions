/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Probe: a plain {@code @Value} field resolves against the profile source. Used by both the
 * positive end-to-end test and the negative test that proves a plain-field reference to a
 * connection-only key fails at bean creation time.
 */
@RestController
public class PlainFieldOnlyProfileController {

    @Value("${greeting}")
    String greeting;

    @GetMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> probe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("greeting", greeting);
        return body;
    }
}
