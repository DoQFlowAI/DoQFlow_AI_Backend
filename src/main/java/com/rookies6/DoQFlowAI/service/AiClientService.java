package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.config.RestClientConfig;
import com.rookies6.DoQFlowAI.dto.ai.AiChat;
import com.rookies6.DoQFlowAI.dto.ai.AiDeleteIndex;
import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;


/// AI Module(DoQScale_LLM2) 내부 API 호출
@Slf4j
@Service
public class AiClientService {

	// SSE data 한 줄을 읽는 용도. 모르는 필드가 와도 무시한다
	private static final JsonMapper SSE_JSON = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();
	private static final String SSE_DATA = "data:";
	private static final String SSE_DONE = "[DONE]";

	private final RestClient aiRestClient;
	// /internal/history 전용 (읽기 180초)
	private final RestClient aiHistoryRestClient;

	public AiClientService(
			@Qualifier(RestClientConfig.AI_REST_CLIENT) RestClient aiRestClient,
			@Qualifier(RestClientConfig.AI_HISTORY_REST_CLIENT) RestClient aiHistoryRestClient) {
		this.aiRestClient = aiRestClient;
		this.aiHistoryRestClient = aiHistoryRestClient;
	}

	/**
	 * @param versionId document_version.id
	 * @param filePath  AI Module이 읽을 원본 파일 경로
	 * @param mimeType  파일 MIME 타입
	 */
	public AiExtract.Response extract(Long versionId, String filePath, String mimeType) {
		AiExtract.Request request = new AiExtract.Request(versionId, filePath, mimeType);
		return call("extract", () -> post(aiRestClient, "/internal/extract", request, AiExtract.Response.class));
	}

	/**
	 * 인접 구간 변경 요약 (AI 모듈 연동 명세서 4.5). 읽기 제한 180초 클라이언트를 쓴다.
	 * 후처리에서는 구간 하나씩 담아 호출한다. 구간이 많아도 대기 시간이 구간 하나 기준으로 묶이고,
	 * 한 구간이 타임아웃돼도 그 구간만 FAILED가 된다.
	 *
	 * @param documentId 문서 ID
	 * @param segments   요약할 구간 (변경 블록이 있는 구간만)
	 */
	public AiHistory.Response history(Long documentId, List<AiHistory.RequestSegment> segments) {
		AiHistory.Request request = new AiHistory.Request(documentId, segments);
		return call("history", () -> post(aiHistoryRestClient, "/internal/history", request, AiHistory.Response.class));
	}

	/**
	 * @param documentId 문서 ID
	 * @param versionId  색인할 document_version.id
	 * @param versionNo  버전 번호
	 */
	public AiIndex.Response index(Long documentId, Long versionId, int versionNo) {
		AiIndex.Request request = new AiIndex.Request(documentId, versionId, versionNo);
		return call("index", () -> post(aiRestClient, "/internal/index", request, AiIndex.Response.class));
	}

	/**
	 * @param documentId    문서 ID
	 * @param keepVersionId 남길 버전 ID. null이면 문서 벡터 전체 삭제
	 */
	public AiDeleteIndex.Response deleteIndex(Long documentId, Long keepVersionId) {
		AiDeleteIndex.Request request = new AiDeleteIndex.Request(documentId, keepVersionId);
		return call("deleteIndex", () -> aiRestClient.method(HttpMethod.DELETE)
				.uri("/internal/index")
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(AiDeleteIndex.Response.class));
	}

	/**
	 * 질의응답 (AI 모듈 연동 명세서 4.4). 읽기 60초 클라이언트를 쓴다.
	 *
	 * 응답은 SSE지만 스트림을 끝까지 받아 한 번에 돌려준다.
	 * Backend는 근거 재확인을 통과하기 전에는 토큰을 Frontend로 보낼 수 없어서(Backend 명세서 6) 어차피 모아야 한다.
	 * 다음은 모두 AiModuleException(502)이다.
	 *   4xx·5xx 응답, 연결 실패·타임아웃, error 이벤트,
	 *   [DONE] 없이 끊긴 스트림, 근거 이벤트 없이 끝난 답변, 읽을 수 없는 data
	 *
	 * @param question 질문
	 * @param scope    검색 범위. 빈 목록이면 호출하지 말아야 하므로 여기서 막는다
	 * @param topK     근거 조각 최대 개수
	 */
	public AiChat.Result chat(String question, List<Long> scope, int topK) {
		if (scope == null || scope.isEmpty()) {
			throw new IllegalArgumentException("scope must not be empty; do not call the AI Module");
		}
		AiChat.Request request = new AiChat.Request(question, scope, topK);
		return call("chat", () -> aiRestClient.post()
				.uri("/internal/chat")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.body(request)
				.exchange((req, res) -> {
					if (res.getStatusCode().isError()) {
						throw new AiModuleException("AI Module chat responded " + res.getStatusCode().value());
					}
					return readChatStream(res.getBody());
				}));
	}

	/**
	 * /internal/chat SSE 본문을 끝까지 읽는다
	 * data: 줄만 보고, 빈 줄·주석(:)·event: 줄은 건너뛴다. [DONE]이 오면 끝이다.
	 */
	static AiChat.Result readChatStream(InputStream body) throws IOException {
		List<String> tokens = new ArrayList<>();
		List<Long> sourceVersionIds = null;
		List<AiChat.Chunk> chunks = List.of();
		boolean noAnswer = false;

		BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			if (!line.startsWith(SSE_DATA)) {
				continue;
			}
			String data = line.substring(SSE_DATA.length()).strip();
			if (SSE_DONE.equals(data)) {
				if (noAnswer) {
					return AiChat.Result.noAnswerResult();
				}
				if (sourceVersionIds == null) {
					throw new AiModuleException("AI Module chat ended without a sources event");
				}
				return new AiChat.Result(List.copyOf(tokens), sourceVersionIds, chunks, false);
			}
			AiChat.Event event = parseEvent(data);
			if (event.error() != null) {
				throw new AiModuleException("AI Module chat sent an error event");
			}
			if (Boolean.TRUE.equals(event.noAnswer())) {
				noAnswer = true;
			}
			if (event.token() != null) {
				tokens.add(event.token());
			}
			if (event.sourceVersionIds() != null) {
				sourceVersionIds = List.copyOf(event.sourceVersionIds());
				chunks = event.chunks() == null ? List.of() : List.copyOf(event.chunks());
			}
		}
		throw new AiModuleException("AI Module chat stream ended without [DONE]");
	}

	private static AiChat.Event parseEvent(String data) {
		try {
			return SSE_JSON.readValue(data, AiChat.Event.class);
		} catch (JacksonException e) {
			// 원문에 답변·문서 내용이 들어 있을 수 있어 로그에 남기지 않는다
			throw new AiModuleException("AI Module chat sent an unreadable event", e);
		}
	}

	/// JSON 본문 전송
	private static <T> T post(RestClient client, String path, Object request, Class<T> responseType) {
		return client.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(responseType);
	}

	/**
	 * 호출 실패를 AiModuleException으로 변경
	 * 원인은 서버 로그에만 남기고, 응답 본문이 없으면 실패로 본다.
	 */
	private static <T> T call(String operation, Supplier<T> request) {
		T response;
		try {
			response = request.get();
		} catch (RestClientException e) {
			log.warn("AI Module {} call failed: {}", operation, e.getMessage());
			throw new AiModuleException("AI Module " + operation + " call failed", e);
		}
		if (response == null) {
			log.warn("AI Module {} returned an empty body", operation);
			throw new AiModuleException("AI Module " + operation + " returned an empty body");
		}
		return response;
	}
}
