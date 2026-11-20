/*
 * Copyright (c) 2018 MyCompany, Inc. All rights reserved.
 */

package com.workato.agent.extension.demo.security;

import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import com.workato.agent.extension.api.AgentExtensionV1;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;

@RestController
public class SecurityExtension {

    private static final Logger log = LoggerFactory.getLogger(SecurityExtension.class);

    @Inject
    private AgentExtensionV1 agent;

    /**
     * Cheap, side-effect-free health probe. Use this (not {@code /computeDigest}) as the connector's
     * {@code test:} lambda so the connection test does not exercise the signing path.
     */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true);
    }

    @PostMapping("/computeDigest")
    public Map<String, Object> computeDigest(@RequestBody Map<String, Object> body) {
        String profile = agent.getContext().getProfileName();
        AgentConfiguration cfg = agent.getContext().getProfileConfiguration();

        // Avoid the config key name `secret` — the agent's SecretsPreprocessor
        // treats any map containing `secret:` as a secret-provider spec and tries
        // to resolve the value through SecretsProviderHolder at config-load time,
        // which fails with "SecretsProvider is not supported" in local-HTTP mode.
        Object secret = cfg.get("signing-secret");
        if (secret == null) {
            log.error("[{}] 'signing-secret' is not configured in extension profile", profile);
            throw new IllegalStateException("'signing-secret' is not configured for profile " + profile);
        }

        Object rawPayload = body == null ? null : body.get("payload");
        if (!(rawPayload instanceof String payload)) {
            // Catches both `null` (NPE on getBytes) and non-String (ClassCastException) —
            // surfaces as 400 via the @ExceptionHandler below instead of a 500 stack trace.
            throw new IllegalArgumentException(
                    "'payload' is required and must be a string; got " +
                            (rawPayload == null ? "null" : rawPayload.getClass().getSimpleName()));
        }
        log.debug("[{}] computing digest for payload of {} chars", profile, payload.length());

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a standard algorithm guaranteed by the JRE spec; keep
            // the signature checked-exception-free so controller method refs
            // can be used with the DeployedAgentLauncher's typed httpPost.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
        digest.update(secret.toString().getBytes(StandardCharsets.UTF_8));
        byte[] result = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
        return Collections.singletonMap("signature", Hex.encodeHexString(result));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }
}
