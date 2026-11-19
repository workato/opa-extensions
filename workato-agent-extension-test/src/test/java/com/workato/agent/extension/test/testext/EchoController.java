/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import com.workato.agent.extension.api.AgentExtensionApiV1;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal extension that reports a @Value-injected greeting plus its profile name.
 */
@RestController
public class EchoController {

    @Autowired
    AgentExtensionApiV1 agent;

    @Value("${greeting}")
    String greeting;

    @GetMapping(value = "/echo", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> echo() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("greeting", greeting);
        body.put("profile", agent.getContext().getProfileName());
        return body;
    }
}
