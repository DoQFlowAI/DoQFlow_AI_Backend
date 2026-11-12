package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.rookies6.DoQFlowAI.config.AiClientProperties;
import com.rookies6.DoQFlowAI.config.RestClientConfig;
import com.rookies6.DoQFlowAI.config.TraceIdFilter;
import com.rookies6.DoQFlowAI.dto.ai.AiDeleteIndex;
import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiClientService 단위 테스트
 *
 * 실제 AI Module 대신 가짜 서버(MockRestServiceServer)가 AI 모듈 연동 명세서 4장 형식으로 응답한다.
 * 요청 주소·메서드·X-Internal-Key·X-Trace-Id·본문이 형식대로 나가는지, 실패가 AiModuleException으로 바뀌는지 확인한다.
 * history는 읽기 제한이 다른 별도 클라이언트를 쓰므로 가짜 서버도 따로 둔다.
 */
class AiClientServiceTest {

	private static final String BASE_URL = "http://ai-module:8000";
	private static final String KEY = "test-internal-key";

	private MockRestServiceServer server;
	private MockRestServiceServer historyServer;
	private AiClientService aiClient;

	@BeforeEach
	void setUp() {
		AiClientProperties properties = new AiClientProperties(
				BASE_URL, KEY, Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofSeconds(180));
		RestClient.Builder builder = RestClientConfig.aiRestClientBuilder(properties, properties.readTimeout());
		RestClient.Builder historyBuilder =
				RestClientConfig.aiRestClientBuilder(properties, properties.historyReadTimeout());
		server = MockRestServiceServer.bindTo(builder).build();
		historyServer = MockRestServiceServer.bindTo(historyBuilder).build();
		aiClient = new AiClientService(builder.build(), historyBuilder.build());
	}

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	@DisplayName("extract는 내부키와 함께 요청하고 본문을 받는다")
	void extractSendsInternalKeyAndReturnsText() {
		server.expect(requestTo(BASE_URL + "/internal/extract"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(RestClientConfig.INTERNAL_KEY_HEADER, KEY))
				.andExpect(content().json("""
						{"versionId": 31, "filePath": "7/3/5f1c.pdf", "mimeType": "application/pdf"}
						"""))
				.andRespond(withSuccess("""
						{"contentText": "첫 문장.\\n둘째 문장.", "pageCount": 3}
						""", MediaType.APPLICATION_JSON));

		AiExtract.Response response = aiClient.extract(31L, "7/3/5f1c.pdf", "application/pdf");

		assertThat(response.contentText()).isEqualTo("첫 문장.\n둘째 문장.");
		assertThat(response.pageCount()).isEqualTo(3);
		server.verify();
	}

	@Test
	@DisplayName("history는 구간별 변경 블록 텍스트를 보내고 요약을 받는다")
	void historySendsBlocksPerSegmentAndReturnsSummaries() {
		historyServer.expect(requestTo(BASE_URL + "/internal/history"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(RestClientConfig.INTERNAL_KEY_HEADER, KEY))
				.andExpect(content().json("""
						{"documentId": 7, "segments": [
						  {"from": 1, "to": 2, "blocks": [
						    {"type": "CHANGE", "before": ["결재는 팀장이 승인한다."], "after": ["결재는 실장이 승인한다."]},
						    {"type": "INSERT", "before": [], "after": ["승인 기한은 3일이다."]}
						  ]}
						]}
						""", true))
				.andRespond(withSuccess("""
						{"segments": [
						  {"from": 1, "to": 2, "changeType": "PROCEDURE", "summary": "승인자 변경", "summaryStatus": "OK"}
						]}
						""", MediaType.APPLICATION_JSON));

		AiHistory.RequestSegment segment = new AiHistory.RequestSegment(1, 2, List.of(
				new AiHistory.Block("CHANGE", List.of("결재는 팀장이 승인한다."), List.of("결재는 실장이 승인한다.")),
				new AiHistory.Block("INSERT", List.of(), List.of("승인 기한은 3일이다."))));

		AiHistory.Response response = aiClient.history(7L, List.of(segment));

		assertThat(response.segments()).containsExactly(
				new AiHistory.Segment(1, 2, "PROCEDURE", "승인자 변경", "OK"));
		historyServer.verify();
	}

	@Test
	@DisplayName("history 응답에 실패 구간이 섞여도 그대로 받는다")
	void historyAcceptsPartiallyFailedSegments() {
		historyServer.expect(requestTo(BASE_URL + "/internal/history"))
				.andRespond(withSuccess("""
						{"segments": [
						  {"from": 2, "to": 3, "changeType": null, "summary": null, "summaryStatus": "FAILED"}
						]}
						""", MediaType.APPLICATION_JSON));

		AiHistory.Response response = aiClient.history(7L, List.of(new AiHistory.RequestSegment(2, 3, List.of())));

		assertThat(response.segments()).containsExactly(new AiHistory.Segment(2, 3, null, null, "FAILED"));
	}

	@Test
	@DisplayName("index는 색인 결과를 받는다")
	void indexReturnsResult() {
		server.expect(requestTo(BASE_URL + "/internal/index"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().json("""
						{"documentId": 7, "versionId": 33, "versionNo": 3}
						"""))
				.andRespond(withSuccess("""
						{"status": "READY", "chunkCount": 42}
						""", MediaType.APPLICATION_JSON));

		AiIndex.Response response = aiClient.index(7L, 33L, 3);

		assertThat(response).isEqualTo(new AiIndex.Response("READY", 42));
		server.verify();
	}

	@Test
	@DisplayName("deleteIndex는 DELETE에 본문을 담아 보낸다")
	void deleteIndexSendsBodyWithDelete() {
		server.expect(requestTo(BASE_URL + "/internal/index"))
				.andExpect(method(HttpMethod.DELETE))
				.andExpect(content().json("""
						{"documentId": 7, "keepVersionId": 33}
						"""))
				.andRespond(withSuccess("""
						{"deleted": 38}
						""", MediaType.APPLICATION_JSON));

		AiDeleteIndex.Response response = aiClient.deleteIndex(7L, 33L);

		assertThat(response.deleted()).isEqualTo(38);
		server.verify();
	}

	@Test
	@DisplayName("traceId가 있으면 X-Trace-Id로 넘긴다")
	void forwardsTraceIdHeader() {
		MDC.put(TraceIdFilter.TRACE_ID, "3f2a9c1e-trace");
		server.expect(requestTo(BASE_URL + "/internal/index"))
				.andExpect(header(RestClientConfig.TRACE_ID_HEADER, "3f2a9c1e-trace"))
				.andRespond(withSuccess("""
						{"status": "READY", "chunkCount": 1}
						""", MediaType.APPLICATION_JSON));
		historyServer.expect(requestTo(BASE_URL + "/internal/history"))
				.andExpect(header(RestClientConfig.TRACE_ID_HEADER, "3f2a9c1e-trace"))
				.andRespond(withSuccess("""
						{"segments": []}
						""", MediaType.APPLICATION_JSON));

		aiClient.index(7L, 33L, 3);
		aiClient.history(7L, List.of());

		server.verify();
		historyServer.verify();
	}

	@Test
	@DisplayName("traceId가 없으면 X-Trace-Id를 보내지 않는다")
	void omitsTraceIdHeaderWhenAbsent() {
		server.expect(requestTo(BASE_URL + "/internal/index"))
				.andExpect(headerDoesNotExist(RestClientConfig.TRACE_ID_HEADER))
				.andRespond(withSuccess("""
						{"status": "READY", "chunkCount": 1}
						""", MediaType.APPLICATION_JSON));

		aiClient.index(7L, 33L, 3);

		server.verify();
	}

	@Test
	@DisplayName("AI Module 5xx는 AiModuleException")
	void serverErrorBecomesAiModuleException() {
		server.expect(requestTo(BASE_URL + "/internal/extract")).andRespond(withServerError());

		assertThatThrownBy(() -> aiClient.extract(31L, "7/3/5f1c.pdf", "application/pdf"))
				.isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("내부키 불일치 401도 AiModuleException")
	void unauthorizedBecomesAiModuleException() {
		server.expect(requestTo(BASE_URL + "/internal/index")).andRespond(withUnauthorizedRequest());

		assertThatThrownBy(() -> aiClient.index(7L, 33L, 3))
				.isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("history 타임아웃도 AiModuleException")
	void historyTimeoutBecomesAiModuleException() {
		historyServer.expect(requestTo(BASE_URL + "/internal/history"))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

		assertThatThrownBy(() -> aiClient.history(7L, List.of()))
				.isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("응답 본문이 없으면 AiModuleException")
	void emptyBodyBecomesAiModuleException() {
		server.expect(requestTo(BASE_URL + "/internal/index")).andRespond(withSuccess());

		assertThatThrownBy(() -> aiClient.index(7L, 33L, 3))
				.isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("history 읽기 제한을 설정하지 않으면 기본 읽기 제한과 같다")
	void historyReadTimeoutDefaultsToReadTimeout() {
		AiClientProperties properties =
				new AiClientProperties(BASE_URL, KEY, Duration.ofSeconds(3), Duration.ofSeconds(60), null);

		assertThat(properties.historyReadTimeout()).isEqualTo(Duration.ofSeconds(60));
	}
}
