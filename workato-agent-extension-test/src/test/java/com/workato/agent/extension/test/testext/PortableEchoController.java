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
 * Minimal {@code @RestController} that intentionally depends on nothing from the agent's
 * {@code extension-api} module — only Spring. Used by {@code DeployedAgentLauncherIntegrationTest} to
 * verify extension mounting against distributions that pre-date the V1 extension API
 * ({@code com.workato.agent.extension.api.AgentExtensionV1}): on such a dist, controllers
 * that autowire {@code AgentExtensionV1} fail Spring class introspection with
 * {@code NoClassDefFoundError}, which has nothing to do with the gateway activation this
 * integration test is really exercising.
 */
@RestController
public class PortableEchoController {

    @Value("${greeting}")
    String greeting;

    @GetMapping(value = "/echo", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> echo() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("greeting", greeting);
        return body;
    }
}
