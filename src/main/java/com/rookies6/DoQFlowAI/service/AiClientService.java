package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.ai.AiDeleteIndex;
import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
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

	public AiClientService(RestClient aiRestClient) {
		this.aiRestClient = aiRestClient;
	}

	/**
	 * @param versionId document_version.id
	 * @param filePath  AI Module이 읽을 원본 파일 경로
	 * @param mimeType  파일 MIME 타입
	 */
	public AiExtract.Response extract(Long versionId, String filePath, String mimeType) {
		AiExtract.Request request = new AiExtract.Request(versionId, filePath, mimeType);
		return call("extract", () -> post("/internal/extract", request, AiExtract.Response.class));
	}

	/**
	 * @param documentId 문서 ID
	 * @param versionIds 요약할 버전 ID 목록 (버전 순서)
	 */
	public AiHistory.Response history(Long documentId, List<Long> versionIds) {
		AiHistory.Request request = new AiHistory.Request(documentId, versionIds);
		return call("history", () -> post("/internal/history", request, AiHistory.Response.class));
	}

	/**
	 * @param documentId 문서 ID
	 * @param versionId  색인할 document_version.id
	 * @param versionNo  버전 번호
	 */
	public AiIndex.Response index(Long documentId, Long versionId, int versionNo) {
		AiIndex.Request request = new AiIndex.Request(documentId, versionId, versionNo);
		return call("index", () -> post("/internal/index", request, AiIndex.Response.class));
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
	private <T> T post(String path, Object request, Class<T> responseType) {
		return aiRestClient.post()
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
