package com.workato.agent.extension.demo.ntlm;

import com.workato.agent.extension.api.AgentExtensionV1;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
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
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SECURITY — plaintext credentials in {@code X-Workato-NTLM-auth}.
 * <p>
 * This endpoint receives the NTLM credentials verbatim in the request header
 * {@value #HEADER_WORKATO_AUTH} (format: {@code user:pass@domain/workstation}).
 * The agent/extension pair is meant to run on the customer's internal network with the Workato
 * SDK connector reaching it over loopback or a trusted intranet hop; the header is <b>not</b>
 * end-to-end encrypted beyond the mTLS tunnel from Workato to the agent.
 * <p>
 * Maintenance rules:
 * <ul>
 *   <li>Never log the raw value of {@link #HEADER_WORKATO_AUTH} (that includes
 *       {@code log.*("... {} ...", authHeader)}, {@code .toString()} on headers maps, request-
 *       logging filters at DEBUG, exception messages that embed the header).</li>
 *   <li>Never persist, forward, or copy the header to any sink other than the
 *       {@link NTCredentials} constructor below. The {@link NtlmAuth} parser copies the password
 *       into a private field — scrub it if you ever add a {@code toString()}.</li>
 *   <li>If you add a controller advice or exception handler, strip this header from any diagnostic
 *       payload before emitting it.</li>
 * </ul>
 */
@RestController
public class NtlmExtension implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NtlmExtension.class);

    private static final String HEADER_WORKATO_URL = "X-Workato-NTLM-url";

    // username:password@domain/workstation — SEE CLASS JAVADOC for redaction rules.
    private static final String HEADER_WORKATO_AUTH = "X-Workato-NTLM-auth";

    @Autowired
    private AgentExtensionV1 agent;

    @Value("${connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${read-timeout-ms:30000}")
    private int readTimeoutMs;

    // Comma-separated allow-list of target hosts. Entries are matched case-insensitively.
    // Supported forms: exact host ("corp.local"), wildcard subdomain ("*.corp.local" matches both
    // "corp.local" and "any.corp.local"). Empty value → no host check is performed (back-compat;
    // recommended to configure explicitly).
    @Value("${ntlm.allowed-hosts:}")
    private String allowedHostsRaw;

    private volatile List<String> allowedHostsCache;

    private volatile CloseableHttpClient httpClient;

    private static URI parseHttpUri(String raw) {
        if (StringUtils.isBlank(raw)) {
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
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return null;
        }
        // Reject URLs carrying userinfo (http://user:pass@host/...) — they would race with the NTLM
        // credentials from the auth header and the password would otherwise leak into an upstream
        // Authorization: Basic ... line.
        if (uri.getUserInfo() != null) {
            return null;
        }
        return uri;
    }

    /**
     * Normalise a host for allow-list comparison: lowercase + Punycode decode of IDN labels so
     * {@code *.münchen.de} matches both the Unicode and the {@code xn--} ASCII form the URI
     * parser hands back.
     */
    private static String normaliseHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        try {
            return IDN.toUnicode(lower);
        } catch (IllegalArgumentException e) {
            return lower;
        }
    }

    private boolean hostAllowed(String host) {
        List<String> patterns = allowedHostPatterns();
        if (patterns.isEmpty()) {
            return true;
        }
        String h = normaliseHost(host);
        for (String p : patterns) {
            if (p.startsWith("*.")) {
                String suffix = p.substring(1);            // ".corp.local"
                String apex = p.substring(2);              // "corp.local"
                if (h.equals(apex) || (h.length() > suffix.length() && h.endsWith(suffix))) {
                    return true;
                }
            }
            else if (p.equals(h)) {
                return true;
            }
        }
        return false;
    }

    private List<String> allowedHostPatterns() {
        List<String> cached = allowedHostsCache;
        if (cached != null) {
            return cached;
        }
        if (StringUtils.isBlank(allowedHostsRaw)) {
            cached = Collections.emptyList();
        }
        else {
            List<String> parsed = new ArrayList<>();
            for (String token : allowedHostsRaw.split(",")) {
                String trimmed = normaliseHost(token.trim());
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
            cached = Collections.unmodifiableList(parsed);
        }
        allowedHostsCache = cached;
        return cached;
    }

    @PostMapping("/request")
    public String request(@RequestHeader(HEADER_WORKATO_AUTH) String authHeader,
                          @RequestHeader(HEADER_WORKATO_URL) String urlHeader,
                          HttpServletResponse httpServletResponse) {

        String profile = agent.getContext().getProfileName();
        NtlmAuth auth = NtlmAuth.build(authHeader);

        // Reject malformed credentials loudly. 401 is the correct status: the request was received
        // but we could not authenticate the caller — keeps clients from mistaking an empty 200
        // body for an upstream success.
        if (auth.username == null || auth.password == null) {
            log.warn("[{}] NTLM credentials missing or malformed", profile);
            httpServletResponse.setStatus(HttpStatus.SC_UNAUTHORIZED);
            return null;
        }

        URI target = parseHttpUri(urlHeader);
        if (target == null) {
            log.warn("[{}] NTLM target URL rejected: {}", profile, urlHeader);
            httpServletResponse.setStatus(HttpStatus.SC_BAD_REQUEST);
            return null;
        }

        if (!hostAllowed(target.getHost())) {
            log.warn("[{}] NTLM target host '{}' not in allow-list (ntlm.allowed-hosts)",
                    profile, target.getHost());
            httpServletResponse.setStatus(HttpStatus.SC_FORBIDDEN);
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
                    // Default PoolingHttpClientConnectionManager caps at 2 per route / 20 total —
                    // adequate for a single-intranet-endpoint reference extension. A customer
                    // scaling this up should raise those caps via a custom
                    // PoolingHttpClientConnectionManager and feed it to .setConnectionManager(...).
                    local = HttpClients.custom().setDefaultRequestConfig(cfg).build();
                    httpClient = local;
                }
            }
        }
        return local;
    }

    @Override
    public void destroy() {
        CloseableHttpClient local = httpClient;
        httpClient = null;
        if (local != null) {
            try {
                local.close();
            } catch (IOException e) {
                log.warn("Failed to close NTLM HTTP client cleanly", e);
            }
        }
    }

    private static class NtlmAuth {
        private String username;
        private String password;
        private String domain;
        private String workstation;

        /**
         * Parse {@code user:pass@domain/workstation} from the Workato-supplied auth header.
         * <p>
         * Caveats of the simple {@code split} logic:
         * <ul>
         *   <li>Splits on the <em>first</em> {@code @}, so passwords containing {@code @} break
         *       — a password like {@code a@b} would be parsed as user={@code a} / domain={@code b}
         *       and the credentials silently fail against the upstream. Callers must ensure
         *       passwords do not contain {@code @} / {@code :} / {@code /}.</li>
         *   <li>Does not validate charset; whitespace is preserved verbatim.</li>
         *   <li>Malformed input yields a {@code NtlmAuth} with {@code null} username/password,
         *       which the controller surfaces as 401.</li>
         * </ul>
         */
        private static NtlmAuth build(String authHeader) {
            NtlmAuth auth = new NtlmAuth();
            if (authHeader == null) {
                return auth;
            }

            int at = authHeader.indexOf('@');
            String credPart;
            String domainPart;
            if (at >= 0) {
                credPart = authHeader.substring(0, at);
                domainPart = authHeader.substring(at + 1);
            }
            else {
                credPart = authHeader;
                domainPart = null;
            }

            int colon = credPart.indexOf(':');
            if (colon < 0) {
                return auth;
            }
            auth.username = credPart.substring(0, colon);
            auth.password = credPart.substring(colon + 1);
            if (auth.username.isEmpty() || auth.password.isEmpty()) {
                auth.username = null;
                auth.password = null;
                return auth;
            }

            if (domainPart != null) {
                int slash = domainPart.indexOf('/');
                if (slash < 0) {
                    auth.domain = domainPart;
                }
                else {
                    auth.domain = domainPart.substring(0, slash);
                    auth.workstation = domainPart.substring(slash + 1);
                }
            }
            return auth;
        }
    }
}
