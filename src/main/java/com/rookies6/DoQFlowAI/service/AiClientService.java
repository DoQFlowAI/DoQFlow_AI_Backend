package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.config.RestClientConfig;
import com.rookies6.DoQFlowAI.dto.ai.AiDeleteIndex;
import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;


/// AI Module(DoQScale_LLM2) 내부 API 호출
@Slf4j
@Service
public class AiClientService {

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
