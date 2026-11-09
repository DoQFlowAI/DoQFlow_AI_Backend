package com.rookies6.DoQFlowAI.dto.ai;


/// [AI 연동] DELETE /internal/index — 문서 색인 삭제 (API 설계서 7)
public final class AiDeleteIndex {

	private AiDeleteIndex() {
	}

	/**
	 * @param documentId
	 * @param keepVersionId
	 */
	public record Request(
			Long documentId,
			Long keepVersionId
	) {
	}

	/// @param deleted 삭제한 벡터 수
	public record Response(
			Integer deleted
	) {
	}
}
