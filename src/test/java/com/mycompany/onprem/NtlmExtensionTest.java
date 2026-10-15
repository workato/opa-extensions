package com.mycompany.onprem;

import com.sun.net.httpserver.HttpServer;
import com.workato.agent.extension.test.AgentExtensionHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class NtlmExtensionTest {

    @RegisterExtension
    static AgentExtensionHarness agent = AgentExtensionHarness.extension("ntlm")
            .controller(NtlmExtension.class)
            .property("connect-timeout-ms", "1000")
            .property("read-timeout-ms", "2000");
    private final AtomicInteger okHits = new AtomicInteger();
    private final AtomicInteger forbiddenHits = new AtomicInteger();
    private HttpServer upstream;
    private int port;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/ok", exchange -> {
            okHits.incrementAndGet();
            byte[] body = "upstream-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        upstream.createContext("/forbidden", exchange -> {
            forbiddenHits.incrementAndGet();
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        upstream.start();
        port = upstream.getAddress().getPort();
    }

    @AfterEach
    void stopUpstream() {
        if (upstream != null) {
            try {
                upstream.stop(0);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void forwardsUpstreamBodyOnHttp200() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@WORKGROUP/WS01")
                        .header("X-Workato-NTLM-url", upstreamUrl("/ok")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("upstream-ok", result.getResponse().getContentAsString());
        assertEquals(1, okHits.get(), "upstream must be contacted exactly once");
    }

    @Test
    void forwardsUpstreamStatusOnNon200() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                        .header("X-Workato-NTLM-url", upstreamUrl("/forbidden")))
                .andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(1, forbiddenHits.get());
    }

    @Test
    void malformedCredentialsSkipHttpCallEntirely() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "no-colon-no-at")
                        .header("X-Workato-NTLM-url", upstreamUrl("/ok")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(0, okHits.get(), "upstream must not be contacted when credentials are malformed");
    }

    @Test
    void emptyCredentialSplitSkipsHttpCall() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user-only-no-colon@DOMAIN")
                        .header("X-Workato-NTLM-url", upstreamUrl("/ok")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
        assertEquals(0, okHits.get(), "upstream must not be contacted when password is missing");
    }

    @Test
    void upstreamFailureSwallowsExceptionAndReturnsEmptyBody() throws Exception {
        upstream.stop(0);  // free the port — next connect yields ECONNREFUSED
        upstream = null;

        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass")
                        .header("X-Workato-NTLM-url", "http://127.0.0.1:" + port + "/ok"))
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
        assertEquals(0, okHits.get(), "upstream must not be contacted for non-http schemes");
    }

    @Test
    void rejectsMalformedUrl() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                        .header("X-Workato-NTLM-url", "ht tp://broken url"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, okHits.get(), "upstream must not be contacted for malformed URLs");
    }

    @Test
    void rejectsUrlWithoutHost() throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", "user:pass@DOMAIN")
                        .header("X-Workato-NTLM-url", "http:///only-path"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, okHits.get(), "upstream must not be contacted when host is missing");
    }

    private void callOkWithAuth(String auth) throws Exception {
        MvcResult result = agent.mockMvc().perform(post(agent.urlFor("/request"))
                        .header("X-Workato-NTLM-auth", auth)
                        .header("X-Workato-NTLM-url", upstreamUrl("/ok")))
                .andReturn();
        assertEquals(200, result.getResponse().getStatus(), "auth=" + auth);
        assertEquals("upstream-ok", result.getResponse().getContentAsString(), "auth=" + auth);
        assertEquals(1, okHits.get(), "auth=" + auth);
    }

    private String upstreamUrl(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
