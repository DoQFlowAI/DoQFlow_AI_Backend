package com.rookies6.DoQFlowAI.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI Module 호출용 RestClient 설정
 *
 * 모든 요청에 X-Internal-Key를 기본 헤더로 넣고, 현재 traceId가 있으면 X-Trace-Id로 넘긴다 (AI 모듈 연동 명세서 7).
 * 읽기 제한이 다른 클라이언트를 두 개 둔다.
 *   aiRestClient        extract, index, deleteIndex — 읽기 60초
 *   aiHistoryRestClient history — 읽기 180초 (구간마다 LLM 호출·재시도로 오래 걸릴 수 있음)
 * RestClient bean이 두 개이므로 주입받을 때 @Qualifier로 이름을 지정한다.
 */
@Configuration
@EnableConfigurationProperties(AiClientProperties.class)
public class RestClientConfig {

	public static final String INTERNAL_KEY_HEADER = "X-Internal-Key";
	public static final String TRACE_ID_HEADER = "X-Trace-Id";

	public static final String AI_REST_CLIENT = "aiRestClient";
	public static final String AI_HISTORY_REST_CLIENT = "aiHistoryRestClient";

	@Bean(name = AI_REST_CLIENT)
	public RestClient aiRestClient(AiClientProperties properties) {
		return aiRestClientBuilder(properties, properties.readTimeout()).build();
	}

	@Bean(name = AI_HISTORY_REST_CLIENT)
	public RestClient aiHistoryRestClient(AiClientProperties properties) {
		return aiRestClientBuilder(properties, properties.historyReadTimeout()).build();
	}

	public static RestClient.Builder aiRestClientBuilder(AiClientProperties properties, Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);

		return RestClient.builder()
				.requestFactory(requestFactory)
				.baseUrl(properties.baseUrl())
				.defaultHeader(INTERNAL_KEY_HEADER, properties.internalKey())
				.requestInterceptor((request, body, execution) -> {
					// traceId는 TraceIdFilter가 서버에서 만든 값이다. 후처리 스레드에는 AsyncConfig가 넘겨준다.
					String traceId = MDC.get(TraceIdFilter.TRACE_ID);
					if (traceId != null) {
						request.getHeaders().set(TRACE_ID_HEADER, traceId);
					}
					return execution.execute(request, body);
				});
	}
}
