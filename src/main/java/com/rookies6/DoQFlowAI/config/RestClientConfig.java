package com.rookies6.DoQFlowAI.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// AI Module 호출용 RestClient 설정
@Configuration
@EnableConfigurationProperties(AiClientProperties.class)
public class RestClientConfig {
	public static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

	@Bean
	public RestClient aiRestClient(AiClientProperties properties) {
		return aiRestClientBuilder(properties).build();
	}

	public static RestClient.Builder aiRestClientBuilder(AiClientProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl(properties.baseUrl())
				.defaultHeader(INTERNAL_KEY_HEADER, properties.internalKey());
	}
}
