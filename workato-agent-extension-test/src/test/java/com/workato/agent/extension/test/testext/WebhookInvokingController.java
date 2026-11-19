/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test.testext;

import com.workato.agent.extension.api.AgentExtensionV1;
import com.workato.agent.extension.api.AgentWebhookV1;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Probe controller that exercises the full {@link AgentWebhookV1} surface — send ladder,
 * wakeup ladder, custom headers, response deserialization — so a single harness-level test can
 * pin that extensions actually see the webhook fake through the normal autowire path.
 */
@RestController
public class WebhookInvokingController {

    @Autowired
    AgentExtensionV1 agent;

    /**
     * POST /fire — forward the request body to a canonical webhook URL via the simplest {@code send}.
     */
    @PostMapping(value = "/fire", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> fire(@RequestBody Map<String, Object> payload) {
        AgentWebhookV1.WebhookResponse response = agent.webhook()
                .send("https://recipe/webhook", payload);
        return Map.of("delivered", true, "status", response.statusCode());
    }

    /**
     * POST /fire-with-headers — exercises the (url, body, headers) overload.
     */
    @PostMapping(value = "/fire-with-headers", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> fireWithHeaders(@RequestBody Map<String, Object> payload) {
        agent.webhook().send(
                "https://recipe/webhook", payload,
                Map.of("X-Request-ID", "fixed-id-for-test"));
        return Map.of("delivered", true);
    }

    /**
     * POST /wake — exercises the simplest wakeup overload.
     */
    @PostMapping(value = "/wake", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> wake() {
        agent.webhook().wakeup("https://recipe/trigger");
        return Map.of("woken", true);
    }

    /**
     * POST /fire-and-parse — sends a payload and deserializes the response via
     * {@code WebhookResponse.as(Class, ObjectMapper)}, passing the agent-provided mapper from
     * {@code AgentToolsV1}.
     */
    @PostMapping(value = "/fire-and-parse", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> fireAndParse(@RequestBody Map<String, Object> payload) {
        AgentWebhookV1.WebhookResponse resp = agent.webhook()
                .send("https://recipe/webhook", payload,
                        Map.of("X-Request-ID", UUID.randomUUID().toString()));
        Ack ack = resp.as(Ack.class, agent.getTools().objectMapper());
        return Map.of("delivered", true, "ackId", ack.id(), "ackCode", ack.code());
    }

    /**
     * POJO we expect to parse out of the recipe's ack body.
     */
    public record Ack(String id, int code) {
    }
}
