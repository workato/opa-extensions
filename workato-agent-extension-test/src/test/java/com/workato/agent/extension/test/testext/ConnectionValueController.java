/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import com.workato.agent.extension.api.AgentExtensionV1;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Probe controller for {@code ConnectionPropertiesEndToEndTest}: pulls one {@code @Value} from
 * the profile side ({@code greeting}) and one from the connection side ({@code api.token}).
 * Confirms both PropertySources are visible to Spring's value resolver.
 */
@RestController
public class ConnectionValueController {

    @Autowired
    AgentExtensionV1 agent;

    @Value("${greeting}")
    String greeting;

    @Value("${api.token}")
    String apiToken;

    @GetMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> probe() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("greeting", greeting);
        body.put("apiToken", apiToken);
        body.put("connectionHash", agent.getContext().getConnectionProperties().contentHash());
        return body;
    }
}
