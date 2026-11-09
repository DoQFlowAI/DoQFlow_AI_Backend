package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.rookies6.DoQFlowAI.config.AiClientProperties;
import com.rookies6.DoQFlowAI.config.RestClientConfig;
import com.rookies6.DoQFlowAI.dto.ai.AiDeleteIndex;
import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiClientService 단위 테스트
 *
 * 실제 AI Module 대신 가짜 서버(MockRestServiceServer)가 API 설계서 7의 형식으로 응답한다.
 * 요청 주소·메서드·X-Internal-Key·본문이 형식대로 나가는지, 실패가 AiModuleException으로 바뀌는지 확인한다.
 */
class AiClientServiceTest {

	private static final String BASE_URL = "http://ai-module:8000";
	private static final String KEY = "test-internal-key";

	private MockRestServiceServer server;
	private AiClientService aiClient;

	@BeforeEach
	void setUp() {
		AiClientProperties properties =
				new AiClientProperties(BASE_URL, KEY, Duration.ofSeconds(3), Duration.ofSeconds(60));
		RestClient.Builder builder = RestClientConfig.aiRestClientBuilder(properties);
		server = MockRestServiceServer.bindTo(builder).build();
		aiClient = new AiClientService(builder.build());
	}

	@Test
	void extract는_내부키와_함께_요청하고_본문을_받는다() {
		server.expect(requestTo(BASE_URL + "/internal/extract"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(RestClientConfig.INTERNAL_KEY_HEADER, KEY))
				.andExpect(content().json("""
						{"versionId": 10, "filePath": "/app/uploads/7/1/a.pdf", "mimeType": "application/pdf"}
						"""))
				.andRespond(withSuccess("""
						{"contentText": "첫 문장.\\n둘째 문장.", "pageCount": 3}
						""", MediaType.APPLICATION_JSON));

		AiExtract.Response response = aiClient.extract(10L, "/app/uploads/7/1/a.pdf", "application/pdf");

		assertThat(response.contentText()).isEqualTo("첫 문장.\n둘째 문장.");
		assertThat(response.pageCount()).isEqualTo(3);
		server.verify();
	}

	@Test
	void history는_구간별_요약과_실패_구간을_함께_받는다() {
		server.expect(requestTo(BASE_URL + "/internal/history"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().json("""
						{"documentId": 7, "versionIds": [10, 11, 12]}
						"""))
				.andRespond(withSuccess("""
						{"segments": [
						  {"from": 1, "to": 2, "changeType": "PROCEDURE", "summary": "승인자 변경", "summaryStatus": "OK"},
						  {"from": 2, "to": 3, "changeType": null, "summary": null, "summaryStatus": "FAILED"}
						]}
						""", MediaType.APPLICATION_JSON));

		AiHistory.Response response = aiClient.history(7L, List.of(10L, 11L, 12L));

		assertThat(response.segments()).containsExactly(
				new AiHistory.Segment(1, 2, "PROCEDURE", "승인자 변경", "OK"),
				new AiHistory.Segment(2, 3, null, null, "FAILED"));
		server.verify();
	}

	@Test
	void index는_색인_결과를_받는다() {
		server.expect(requestTo(BASE_URL + "/internal/index"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().json("""
						{"documentId": 7, "versionId": 12, "versionNo": 3}
						"""))
				.andRespond(withSuccess("""
						{"status": "OK", "chunkCount": 42}
						""", MediaType.APPLICATION_JSON));

		AiIndex.Response response = aiClient.index(7L, 12L, 3);

		assertThat(response).isEqualTo(new AiIndex.Response("OK", 42));
		server.verify();
	}

	@Test
	void deleteIndex는_DELETE에_본문을_담아_보낸다() {
		server.expect(requestTo(BASE_URL + "/internal/index"))
				.andExpect(method(HttpMethod.DELETE))
				.andExpect(content().json("""
						{"documentId": 7, "keepVersionId": 12}
						"""))
				.andRespond(withSuccess("""
						{"deleted": 38}
						""", MediaType.APPLICATION_JSON));

		AiDeleteIndex.Response response = aiClient.deleteIndex(7L, 12L);

		assertThat(response.deleted()).isEqualTo(38);
		server.verify();
	}

	@Test
	void AI_Module_5xx는_AiModuleException() {
		server.expect(requestTo(BASE_URL + "/internal/extract")).andRespond(withServerError());

		assertThatThrownBy(() -> aiClient.extract(10L, "/app/uploads/7/1/a.pdf", "application/pdf"))
				.isInstanceOf(AiModuleException.class);
	}

	@Test
	void 내부키_불일치_401도_AiModuleException() {
		server.expect(requestTo(BASE_URL + "/internal/index")).andRespond(withUnauthorizedRequest());

		assertThatThrownBy(() -> aiClient.index(7L, 12L, 3))
				.isInstanceOf(AiModuleException.class);
	}

	@Test
	void 타임아웃도_AiModuleException() {
		server.expect(requestTo(BASE_URL + "/internal/history"))
				.andRespond(withException(new SocketTimeoutException("read timed out")));

		assertThatThrownBy(() -> aiClient.history(7L, List.of(10L, 11L)))
				.isInstanceOf(AiModuleException.class);
	}

	@Test
	void 응답_본문이_없으면_AiModuleException() {
		server.expect(requestTo(BASE_URL + "/internal/index")).andRespond(withSuccess());

		assertThatThrownBy(() -> aiClient.index(7L, 12L, 3))
				.isInstanceOf(AiModuleException.class);
	}
}
