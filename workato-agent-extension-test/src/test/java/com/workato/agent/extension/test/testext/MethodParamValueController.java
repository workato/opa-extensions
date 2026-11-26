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
 * Probe controller for {@code MethodParamValueControllerTest}: receives the profile key
 * {@code greeting} and the connection key {@code api.token} as {@code @Value}-annotated
 * handler-method parameters. Pins that method-parameter injection works for both
 * PropertySources, exactly like field injection does on {@link ConnectionValueController}.
 */
@RestController
public class MethodParamValueController {

    @GetMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> probe(
            @Value("${greeting}") String greeting,
            @Value("${api.token}") String apiToken,
            @Value("${api.timeout-ms:3000}") int timeoutMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("greeting", greeting);
        body.put("apiToken", apiToken);
        body.put("timeoutMs", timeoutMs);
        return body;
    }
}
