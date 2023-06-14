package com.mycompany.onprem;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class NtlmExtension {

	private static final String HEADER_WORKATO_URL = "X-Workato-NTLM-url";

	// username:password@domain/workstation
	private static final String HEADER_WORKATO_AUTH = "X-Workato-NTLM-auth";

	@Inject
	private Environment env;

	private CloseableHttpClient httpClient = HttpClients.createDefault();

	@RequestMapping(path = "/request", method = RequestMethod.POST)
	public @ResponseBody String request(@RequestHeader(HEADER_WORKATO_AUTH) String authHeader,
										@RequestHeader(HEADER_WORKATO_URL) String urlHeader,
										HttpServletResponse httpServletResponse) {

		NtlmAuth auth = NtlmAuth.build(authHeader);

		if (auth.username != null && auth.password != null) {
			try {
				CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
				credentialsProvider.setCredentials(AuthScope.ANY, new NTCredentials(auth.username, auth.password, auth.workstation, auth.domain));

				HttpClientContext context = HttpClientContext.create();
				context.setCredentialsProvider(credentialsProvider);

				HttpGet httpGet = new HttpGet(urlHeader);

				try (CloseableHttpResponse response = httpClient.execute(httpGet, context)) {
					HttpEntity entity = response.getEntity();
					if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
						return EntityUtils.toString(entity);
					} else {
						httpServletResponse.setStatus(response.getStatusLine().getStatusCode());
					}
				}
			} catch (Exception e) {
				e.printStackTrace(System.err);
			}
		}

		return null;
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
					} else if (domainSplit.length == 1) {
						auth.domain = domainSplit[0];
					}
				}
			} else if (authSplit.length == 1) {
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
