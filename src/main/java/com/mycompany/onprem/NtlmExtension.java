package com.mycompany.onprem;

import com.workato.agent.extension.api.AgentExtensionApiV1;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;

@RestController
public class NtlmExtension {

    private static final Logger log = LoggerFactory.getLogger(NtlmExtension.class);

    private static final String HEADER_WORKATO_URL = "X-Workato-NTLM-url";

    // username:password@domain/workstation
    private static final String HEADER_WORKATO_AUTH = "X-Workato-NTLM-auth";

    @Autowired
    private AgentExtensionApiV1 agent;

    @Value("${connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${read-timeout-ms:30000}")
    private int readTimeoutMs;

    private volatile CloseableHttpClient httpClient;

    private static URI parseHttpUri(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return null;
        }
        return uri;
    }

    @PostMapping("/request")
    public String request(@RequestHeader(HEADER_WORKATO_AUTH) String authHeader,
                          @RequestHeader(HEADER_WORKATO_URL) String urlHeader,
                          HttpServletResponse httpServletResponse) {

        String profile = agent.getContext().getProfileName();
        NtlmAuth auth = NtlmAuth.build(authHeader);

        if (auth.username == null || auth.password == null) {
            log.warn("[{}] NTLM credentials missing or malformed", profile);
            return null;
        }

        URI target = parseHttpUri(urlHeader);
        if (target == null) {
            log.warn("[{}] NTLM target URL rejected: {}", profile, urlHeader);
            httpServletResponse.setStatus(HttpStatus.SC_BAD_REQUEST);
            return null;
        }

        try {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new NTCredentials(auth.username, auth.password, auth.workstation, auth.domain));

            HttpClientContext context = HttpClientContext.create();
            context.setCredentialsProvider(credentialsProvider);

            HttpGet httpGet = new HttpGet(target);

            try (CloseableHttpResponse response = client().execute(httpGet, context)) {
                HttpEntity entity = response.getEntity();
                int status = response.getStatusLine().getStatusCode();
                if (status == HttpStatus.SC_OK) {
                    return EntityUtils.toString(entity);
                }
                log.warn("[{}] NTLM request to {} returned HTTP {}", profile, urlHeader, status);
                httpServletResponse.setStatus(status);
            }
        } catch (Exception e) {
            log.error("[{}] NTLM request to {} failed", profile, urlHeader, e);
        }

        return null;
    }

    private CloseableHttpClient client() {
        CloseableHttpClient local = httpClient;
        if (local == null) {
            synchronized (this) {
                local = httpClient;
                if (local == null) {
                    RequestConfig cfg = RequestConfig.custom()
                            .setConnectTimeout(connectTimeoutMs)
                            .setSocketTimeout(readTimeoutMs)
                            .build();
                    local = HttpClients.custom().setDefaultRequestConfig(cfg).build();
                    httpClient = local;
                }
            }
        }
        return local;
    }

    private static class NtlmAuth {
        private String username;
        private String password;
        private String domain;
        private String workstation;

        private static NtlmAuth build(String authHeader) {
            NtlmAuth auth = new NtlmAuth();

            String[] authSplit = authHeader.split("@");
            if (authSplit.length == 2) {
                buildCredentials(auth, authSplit[0]);
                if (auth.username != null) {
                    String[] domainSplit = authSplit[1].split("/");
                    if (domainSplit.length == 2) {
                        auth.domain = domainSplit[0];
                        auth.workstation = domainSplit[1];
                    }
                    else if (domainSplit.length == 1) {
                        auth.domain = domainSplit[0];
                    }
                }
            }
            else if (authSplit.length == 1) {
                buildCredentials(auth, authSplit[0]);
            }

            return auth;
        }

        private static void buildCredentials(NtlmAuth auth, String input) {
            String[] credSplit = input.split(":");
            if (credSplit.length == 2) {
                auth.username = credSplit[0];
                auth.password = credSplit[1];
            }
        }
    }
}
