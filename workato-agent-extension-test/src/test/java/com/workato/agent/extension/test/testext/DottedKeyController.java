/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Extension that @Value-injects YAML keys containing literal dots (not nesting).
 */
@RestController
public class DottedKeyController {

    @Value("${mail.pop3.username}")
    String username;

    @Value("${mail.pop3.password}")
    String password;

    @GetMapping(value = "/mail", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> mail() {
        return Map.of("u", username, "p", password);
    }
}
