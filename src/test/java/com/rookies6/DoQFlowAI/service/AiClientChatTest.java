package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.rookies6.DoQFlowAI.config.AiClientProperties;
import com.rookies6.DoQFlowAI.config.RestClientConfig;
import com.rookies6.DoQFlowAI.dto.ai.AiChat;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AiClientService.chat 단위 테스트 (AI 모듈 연동 명세서 4.4)
 *
 * 가짜 AI Module이 SSE 본문을 돌려준다. 스트림을 끝까지 읽어 토큰·근거·근거 없음을 모으는지,
 * 오류 이벤트·끊긴 스트림·근거 이벤트 누락이 모두 AiModuleException(502)으로 바뀌는지 확인한다.
 */
class AiClientChatTest {

	private static final String BASE_URL = "http://ai-module:8000";
	private static final String KEY = "test-internal-key";

	private MockRestServiceServer server;
	private AiClientService aiClient;

	@BeforeEach
	void setUp() {
		AiClientProperties properties = new AiClientProperties(
				BASE_URL, KEY, Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofSeconds(180));
		RestClient.Builder builder = RestClientConfig.aiRestClientBuilder(properties, properties.readTimeout());
		RestClient.Builder historyBuilder =
				RestClientConfig.aiRestClientBuilder(properties, properties.historyReadTimeout());
		server = MockRestServiceServer.bindTo(builder).build();
		aiClient = new AiClientService(builder.build(), historyBuilder.build());
	}

	private void respond(String sseBody) {
		server.expect(requestTo(BASE_URL + "/internal/chat"))
				.andRespond(withSuccess(sseBody, MediaType.TEXT_EVENT_STREAM));
	}

	@Test
	@DisplayName("토큰과 근거를 끝까지 모아 돌려준다")
	void collectsTokensAndSourcesUntilDone() {
		server.expect(requestTo(BASE_URL + "/internal/chat"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(RestClientConfig.INTERNAL_KEY_HEADER, KEY))
				.andExpect(header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE))
				.andExpect(content().json("""
						{"question": "출장비 정산 기한이 언제야?", "scope": [33, 41], "topK": 5}
						"""))
				.andRespond(withSuccess("""
						data: {"token": "출장비는"}

						data: {"token": " 귀국 후 14일 이내"}

						data: {"sourceVersionIds": [33], "chunks": [{"versionId": 33, "documentId": 7, "versionNo": 3, "pageNumber": 12, "chunkIndex": 5, "chunkText": "정산은 14일 이내"}]}

						data: [DONE]

						""", MediaType.TEXT_EVENT_STREAM));

		AiChat.Result result = aiClient.chat("출장비 정산 기한이 언제야?", List.of(33L, 41L), 5);

		assertThat(result.noAnswer()).isFalse();
		assertThat(result.tokens()).containsExactly("출장비는", " 귀국 후 14일 이내");
		assertThat(result.answer()).isEqualTo("출장비는 귀국 후 14일 이내");
		assertThat(result.sourceVersionIds()).containsExactly(33L);
		assertThat(result.chunks()).singleElement().satisfies(chunk -> {
			assertThat(chunk.versionId()).isEqualTo(33L);
			assertThat(chunk.pageNumber()).isEqualTo(12);
			assertThat(chunk.chunkText()).isEqualTo("정산은 14일 이내");
		});
		server.verify();
	}

	@Test
	@DisplayName("근거 없음이면 noAnswer로 돌려준다")
	void returnsNoAnswerResult() {
		respond("""
				data: {"noAnswer": true}

				data: [DONE]

				""");

		AiChat.Result result = aiClient.chat("질문", List.of(33L), 5);

		assertThat(result.noAnswer()).isTrue();
		assertThat(result.tokens()).isEmpty();
	}

	@Test
	@DisplayName("모르는 필드와 주석 줄은 무시한다")
	void ignoresUnknownFieldsAndCommentLines() {
		respond("""
				: keep-alive
				event: message
				data: {"token": "답", "extra": 1}

				data: {"sourceVersionIds": [33], "chunks": [{"versionId": 33, "chunkText": "조각", "score": 0.9}]}

				data: [DONE]
				""");

		AiChat.Result result = aiClient.chat("질문", List.of(33L), 5);

		assertThat(result.answer()).isEqualTo("답");
		assertThat(result.chunks()).hasSize(1);
	}

	@Test
	@DisplayName("error 이벤트는 502로 바꾼다")
	void errorEventBecomes502() {
		respond("""
				data: {"token": "출장비는"}

				data: {"error": "AI_UNAVAILABLE"}

				data: [DONE]

				""");

		assertThatThrownBy(() -> aiClient.chat("질문", List.of(33L), 5)).isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("DONE 없이 끊긴 스트림은 502다")
	void streamWithoutDoneIs502() {
		respond("""
				data: {"token": "출장비는"}

				""");

		assertThatThrownBy(() -> aiClient.chat("질문", List.of(33L), 5)).isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("근거 이벤트 없이 끝난 답변은 502다")
	void answerWithoutSourcesEventIs502() {
		respond("""
				data: {"token": "출장비는"}

				data: [DONE]

				""");

		assertThatThrownBy(() -> aiClient.chat("질문", List.of(33L), 5)).isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("읽을 수 없는 data는 502다")
	void unreadableDataIs502() {
		respond("""
				data: not-json

				data: [DONE]

				""");

		assertThatThrownBy(() -> aiClient.chat("질문", List.of(33L), 5)).isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("AI Module의 4xx·5xx와 타임아웃은 502다")
	void aiModuleErrorsAndTimeoutAre502() {
		server.expect(requestTo(BASE_URL + "/internal/chat")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> aiClient.chat("질문", List.of(33L), 5)).isInstanceOf(AiModuleException.class);

		server.reset();
		server.expect(requestTo(BASE_URL + "/internal/chat")).andRespond(withServerError());
		assertThatThrownBy(() -> aiClient.chat("질문", List.of(33L), 5)).isInstanceOf(AiModuleException.class);

		server.reset();
		server.expect(requestTo(BASE_URL + "/internal/chat"))
				.andRespond(withException(new SocketTimeoutException("read timed out")));
		assertThatThrownBy(() -> aiClient.chat("질문", List.of(33L), 5)).isInstanceOf(AiModuleException.class);
	}

	@Test
	@DisplayName("scope가 비면 AI Module에 요청을 보내지 않는다")
	void doesNotCallAiModuleWithEmptyScope() {
		assertThatThrownBy(() -> aiClient.chat("질문", List.of(), 5)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> aiClient.chat("질문", null, 5)).isInstanceOf(IllegalArgumentException.class);

		// 기대한 요청이 하나도 없으므로, 요청이 나갔다면 verify 전에 이미 실패한다
		server.verify();
	}
}
