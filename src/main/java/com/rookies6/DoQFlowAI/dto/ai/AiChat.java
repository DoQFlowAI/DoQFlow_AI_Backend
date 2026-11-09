package com.rookies6.DoQFlowAI.dto.ai;

import java.util.List;

/// 질의응답 ( POST /internal/chat, AI 모듈 연동 명세서 4.4 )
public final class AiChat {

	private AiChat() {
	}

	/**
	 * @param question 질문 (1~1000자)
	 * @param scope    검색해도 되는 document_version.id. 비어 있으면 Backend가 호출하지 않는다
	 * @param topK     근거 조각 최대 개수 (1~10)
	 */
	public record Request(
			String question,
			List<Long> scope,
			int topK
	) {
	}

	/**
	 * SSE data 한 줄. 이벤트 종류마다 채워지는 필드가 다르다.
	 *   토큰      token
	 *   근거      sourceVersionIds, chunks (토큰 뒤 마지막 이벤트)
	 *   근거 없음 noAnswer = true
	 *   오류      error = "AI_UNAVAILABLE"
	 */
	public record Event(
			String token,
			List<Long> sourceVersionIds,
			List<Chunk> chunks,
			Boolean noAnswer,
			String error
	) {
	}

	/**
	 * 답변 근거 조각. 문서 이름은 오지 않으므로 Backend가 versionId로 찾는다.
	 *
	 * @param pageNumber PDF 페이지. 그 외 형식은 null
	 */
	public record Chunk(
			Long versionId,
			Long documentId,
			Integer versionNo,
			Integer pageNumber,
			Integer chunkIndex,
			String chunkText
	) {
	}

	/**
	 * 스트림을 끝까지 받아 모은 결과
	 *
	 * @param tokens           받은 순서대로의 토큰. 근거 없음이면 비어 있다
	 * @param sourceVersionIds LLM에 넘긴 조각들의 버전 ID 전체. Backend가 scope와 다시 대조한다
	 * @param chunks           근거 조각
	 * @param noAnswer         AI Module이 근거 없음으로 끝냈는지
	 */
	public record Result(
			List<String> tokens,
			List<Long> sourceVersionIds,
			List<Chunk> chunks,
			boolean noAnswer
	) {

		public static Result noAnswerResult() {
			return new Result(List.of(), List.of(), List.of(), true);
		}

		public String answer() {
			return String.join("", tokens);
		}
	}
}
