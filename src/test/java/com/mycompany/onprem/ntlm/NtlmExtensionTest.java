package com.mycompany.onprem.ntlm;

import com.workato.agent.extension.test.AgentExtensionHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class NtlmExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("ntlm")
            .controller(NtlmExtension.class)
            .property("connect-timeout-ms", "1000")
            .property("read-timeout-ms", "2000");

    private LocalUpstreamServer upstream;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = LocalUpstreamServer.start();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    void forwardsUpstreamBodyOnHttp200() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@WORKGROUP/WS01")
                        .header("X-Workato-NTLM-url", upstream.urlFor("/ok")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(LocalUpstreamServer.OK_BODY, result.getResponse().getContentAsString());
        assertEquals(1, upstream.okHits.get(), "upstream must be contacted exactly once");
    }

    @Test
    void forwardsUpstreamStatusOnNon200() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                        .header("X-Workato-NTLM-url", upstream.urlFor("/forbidden")))
                .andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(1, upstream.forbiddenHits.get());
    }

    @Test
    void malformedCredentialsSkipHttpCallEntirely() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "no-colon-no-at")
                        .header("X-Workato-NTLM-url", upstream.urlFor("/ok")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(0, upstream.okHits.get(), "upstream must not be contacted when credentials are malformed");
    }

    @Test
    void emptyCredentialSplitSkipsHttpCall() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user-only-no-colon@DOMAIN")
                        .header("X-Workato-NTLM-url", upstream.urlFor("/ok")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(0, upstream.okHits.get(), "upstream must not be contacted when password is missing");
    }

    @Test
    void upstreamFailureSwallowsExceptionAndReturnsEmptyBody() throws Exception {
        int formerPort = upstream.port();
        upstream.stop();  // free the port — next connect yields ECONNREFUSED

        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass")
                        .header("X-Workato-NTLM-url", "http://127.0.0.1:" + formerPort + "/ok"))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
    }

    @Test
    void credentialParserAcceptsUserPassAtDomainSlashWorkstation() throws Exception {
        callOkWithAuth("user:pass@DOMAIN/WS01");
    }

    @Test
    void credentialParserAcceptsUserPassAtDomainWithoutWorkstation() throws Exception {
        callOkWithAuth("user:pass@DOMAIN");
    }

    @Test
    void credentialParserAcceptsUserPassWithoutDomain() throws Exception {
        callOkWithAuth("user:pass");
    }

    @Test
    void rejectsNonHttpScheme() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                        .header("X-Workato-NTLM-url", "file:///etc/passwd"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, upstream.okHits.get(), "upstream must not be contacted for non-http schemes");
    }

    @Test
    void rejectsMalformedUrl() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                        .header("X-Workato-NTLM-url", "ht tp://broken url"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, upstream.okHits.get(), "upstream must not be contacted for malformed URLs");
    }

    @Test
    void rejectsUrlWithoutHost() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                        .header("X-Workato-NTLM-url", "http:///only-path"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, upstream.okHits.get(), "upstream must not be contacted when host is missing");
    }

    private void callOkWithAuth(String auth) throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", auth)
                        .header("X-Workato-NTLM-url", upstream.urlFor("/ok")))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), "auth=" + auth);
        assertEquals(LocalUpstreamServer.OK_BODY, result.getResponse().getContentAsString(), "auth=" + auth);
        assertEquals(1, upstream.okHits.get(), "auth=" + auth);
    }

    @Nested
    class WithAllowList {

        @RegisterExtension
        AgentExtensionHarness restrictedAgent = AgentExtensionHarness.extension("ntlm")
                .controller(NtlmExtension.class)
                .property("connect-timeout-ms", "1000")
                .property("read-timeout-ms", "2000")
                .property("ntlm.allowed-hosts", "127.0.0.1, *.corp.local");

        @Test
        void allowsExactHostInAllowList() throws Exception {
            MvcResult result = restrictedAgent.mockMvc().perform(post(restrictedAgent.urlFor("/request"))
                            .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                            .header("X-Workato-NTLM-url", upstream.urlFor("/ok")))
                    .andReturn();

            assertEquals(200, result.getResponse().getStatus());
            assertEquals(LocalUpstreamServer.OK_BODY, result.getResponse().getContentAsString());
            assertEquals(1, upstream.okHits.get(), "upstream must be contacted when host is allow-listed");
        }

        @Test
        void rejectsHostNotInAllowList() throws Exception {
            MvcResult result = restrictedAgent.mockMvc().perform(post(restrictedAgent.urlFor("/request"))
                            .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                            .header("X-Workato-NTLM-url", "http://localhost:" + upstream.port() + "/ok"))
                    .andReturn();

            assertEquals(403, result.getResponse().getStatus());
            assertEquals(0, upstream.okHits.get(), "upstream must not be contacted when host is not allow-listed");
        }
    }

    @Nested
    class WithWildcardAllowList {

        @RegisterExtension
        AgentExtensionHarness wildcardAgent = AgentExtensionHarness.extension("ntlm")
                .controller(NtlmExtension.class)
                .property("connect-timeout-ms", "1000")
                .property("read-timeout-ms", "2000")
                .property("ntlm.allowed-hosts", "*.0.0.1");

        @Test
        void wildcardMatchesSubdomain() throws Exception {
            // "*.0.0.1" matches "127.0.0.1" because the host ends with ".0.0.1" and has a longer prefix.
            MvcResult result = wildcardAgent.mockMvc().perform(post(wildcardAgent.urlFor("/request"))
                            .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                            .header("X-Workato-NTLM-url", upstream.urlFor("/ok")))
                    .andReturn();

            assertEquals(200, result.getResponse().getStatus());
            assertEquals(LocalUpstreamServer.OK_BODY, result.getResponse().getContentAsString());
            assertEquals(1, upstream.okHits.get());
        }

        @Test
        void wildcardDoesNotMatchUnrelatedHost() throws Exception {
            MvcResult result = wildcardAgent.mockMvc().perform(post(wildcardAgent.urlFor("/request"))
                            .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                            .header("X-Workato-NTLM-url", "http://localhost:" + upstream.port() + "/ok"))
                    .andReturn();

            assertEquals(403, result.getResponse().getStatus());
            assertEquals(0, upstream.okHits.get());
        }
    }
}
