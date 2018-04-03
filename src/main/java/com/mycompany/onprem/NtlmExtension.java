package com.mycompany.onprem;

import java.net.URL;
import java.util.Map;

import javax.inject.Inject;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class NtlmExtension {

	@Inject
	private Environment env;

	private CloseableHttpClient httpClient = HttpClients.createDefault();

	@RequestMapping(path = "/request", method = RequestMethod.POST)
	public @ResponseBody String ntlmUrl(@RequestBody Map<String, Object> body) {
		String username = (String)body.get("username");
		String password = (String)body.get("password");
		String url = (String)body.get("url");

		if (url != null) {
			try {
				URL urlObj = new URL(url);

				CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
				credentialsProvider.setCredentials(AuthScope.ANY, new NTCredentials(username, password, null, null));

				HttpHost target = new HttpHost(urlObj.getHost(), urlObj.getPort(), urlObj.getProtocol());

				HttpClientContext context = HttpClientContext.create();
				context.setCredentialsProvider(credentialsProvider);

				HttpGet httpGet = new HttpGet(urlObj.getFile());

				try (CloseableHttpResponse response = httpClient.execute(target, httpGet, context)) {
					HttpEntity entity = response.getEntity();
					if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
						return EntityUtils.toString(entity);
					}
				}
			} catch (Exception e) {
				e.printStackTrace(System.err);
			}
		}

		return "";
	}

}
