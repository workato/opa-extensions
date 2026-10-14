/*
 * Copyright (c) 2018 MyCompany, Inc. All rights reserved.
 */

package com.mycompany.onprem;

import com.workato.agent.extension.api.AgentConfigurationV1;
import com.workato.agent.extension.api.AgentExtensionApiV1;
import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

@RestController
public class SecurityExtension {

    private static final Logger log = LoggerFactory.getLogger(SecurityExtension.class);

    @Inject
    private AgentExtensionApiV1 agent;

    @PostMapping("/computeDigest")
    public Map<String, Object> computeDigest(@RequestBody Map<String, Object> body) throws Exception {
        String profile = agent.getContext().getProfileName();
        AgentConfigurationV1 cfg = agent.getContext().getProfileConfiguration();

        Object secret = cfg.get("secret");
        if (secret == null) {
            log.error("[{}] 'secret' is not configured in extension profile", profile);
            throw new IllegalStateException("'secret' is not configured for profile " + profile);
        }

        String payload = (String) body.get("payload");
        log.debug("[{}] computing digest for payload of {} chars",
                profile, payload == null ? 0 : payload.length());

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(secret.toString().getBytes(StandardCharsets.UTF_8));
        byte[] result = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
        return Collections.singletonMap("signature", Hex.encodeHexString(result));
    }
}
