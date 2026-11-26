/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Negative-path probe: references a connection-only key ({@code api.token}) through a <em>plain</em>
 * {@code @Value} field. By design, plain-field {@code @Value} only reaches the profile source, so
 * bean creation must fail with an unresolvable-placeholder error.
 */
@RestController
public class PlainFieldReferencesConnectionKeyController {

    @Value("${api.token}")
    String apiToken;

    @GetMapping("/probe")
    public String probe() {
        return apiToken;
    }
}
