package com.rookies6.DoQFlowAI.dto.ai;

///  버전 본문 색인 ( POST /internal/index )
public final class AiIndex {

	private AiIndex() {
	}

	/**
	 * @param documentId 문서 ID
	 * @param versionId  색인할 document_version.id
	 * @param versionNo  버전 번호
	 */
	public record Request(
			Long documentId,
			Long versionId,
			int versionNo
	) {
	}

	/**
	 * @param status     색인 결과 상태
	 * @param chunkCount 만든 청크 수
	 */
	public record Response(
			String status,
			Integer chunkCount
	) {
	}
}
