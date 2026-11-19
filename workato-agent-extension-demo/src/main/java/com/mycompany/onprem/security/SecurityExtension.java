/*
 * Copyright (c) 2018 MyCompany, Inc. All rights reserved.
 */

package com.mycompany.onprem.security;

import com.workato.agent.extension.api.AgentExtensionApiV1;
import com.workato.agent.extension.api.AgentExtensionContextV1.AgentConfiguration;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;

@RestController
public class SecurityExtension {

    private static final Logger log = LoggerFactory.getLogger(SecurityExtension.class);

    @Inject
    private AgentExtensionApiV1 agent;

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

        String payload = (String) body.get("payload");
        log.debug("[{}] computing digest for payload of {} chars",
                profile, payload == null ? 0 : payload.length());

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
}
