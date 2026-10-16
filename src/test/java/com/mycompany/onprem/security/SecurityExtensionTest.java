package com.mycompany.onprem.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workato.agent.extension.test.AgentExtensionHarness;
import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SecurityExtensionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("security")
            .yaml("extensions/security.yml");  // signing-secret: test-secret

    private static String sign(AgentExtensionHarness h, String payload) throws Exception {
        String json = MAPPER.writeValueAsString(Map.of("payload", payload));
        MvcResult result = h.mockMvc()
                .perform(post(h.urlFor("/computeDigest"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(json.getBytes(StandardCharsets.UTF_8)))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                "unexpected status, body=" + result.getResponse().getContentAsString());
        Map<?, ?> body = MAPPER.readValue(result.getResponse().getContentAsString(), Map.class);
        return body.get("signature").toString();
    }

    private static String sha256Hex(String secret, String payload) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(secret.getBytes(StandardCharsets.UTF_8));
        return Hex.encodeHexString(md.digest(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T unwrap(Throwable t, Class<T> target) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (target.isInstance(cur)) {
                return (T) cur;
            }
        }
        return null;
    }

    @Test
    void computesExpectedSha256OfSecretThenPayload() throws Exception {
        String sig = sign(agent, "hello");
        assertEquals(sha256Hex("test-secret", "hello"), sig);
    }

    @Test
    void sameInputProducesSameSignature() throws Exception {
        assertEquals(sign(agent, "payload-1"), sign(agent, "payload-1"));
    }

    @Test
    void differentPayloadsProduceDifferentSignatures() throws Exception {
        assertNotEquals(sign(agent, "payload-1"), sign(agent, "payload-2"));
    }

    @Test
    void differentSecretsProduceDifferentSignaturesForSamePayload() throws Exception {
        AgentExtensionHarness altSecret = AgentExtensionHarness.extension("security-alt")
                .controller(SecurityExtension.class)
                .property("signing-secret", "other-secret")
                .start();
        try {
            assertNotEquals(sign(agent, "same-payload"), sign(altSecret, "same-payload"));
        } finally {
            altSecret.stop();
        }
    }

    @Test
    void signatureIs64CharsOfLowercaseHex() throws Exception {
        String sig = sign(agent, "hello");
        assertEquals(64, sig.length());
        assertTrue(sig.matches("[0-9a-f]+"), "unexpected signature: " + sig);
    }

    @Test
    void payloadIsHashedAsUtf8Bytes() throws Exception {
        String payload = "кириллица"; // "кириллица"
        assertEquals(sha256Hex("test-secret", payload), sign(agent, payload));
    }

    @Test
    void missingSecretRaisesIllegalStateCarryingProfileName() {
        AgentExtensionHarness noSecret = AgentExtensionHarness.extension("security-no-secret")
                .controller(SecurityExtension.class)
                .start();
        try {
            Throwable thrown = assertThrows(Throwable.class, () ->
                    noSecret.mockMvc().perform(post(noSecret.urlFor("/computeDigest"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payload\":\"hello\"}")));
            IllegalStateException cause = unwrap(thrown, IllegalStateException.class);
            assertNotNull(cause, "expected IllegalStateException in cause chain, got: " + thrown);
            assertTrue(cause.getMessage().contains("security-no-secret"),
                    "message should carry the profile name, got: " + cause.getMessage());
        } finally {
            noSecret.stop();
        }
    }
}
