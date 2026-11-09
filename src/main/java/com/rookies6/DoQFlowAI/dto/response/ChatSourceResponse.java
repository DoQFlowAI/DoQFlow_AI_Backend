package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.MessageSource;

/**
 * 답변 근거 한 건 < API 26 sources, API 27 sources 이벤트 >
 *
 * <pre>
 * { "documentId": 7, "documentName": "인증 설계서", "versionNo": 4, "pageNumber": 12, "chunkText": "..." }
 * </pre>
 *
 * @param documentName 답변 당시 문서 이름 (이름을 바꿔도 유지)
 * @param versionNo    답변 당시 버전 번호
 * @param pageNumber   PDF 페이지. 그 외 형식은 null
 */
public record ChatSourceResponse(
		Long documentId,
		String documentName,
		int versionNo,
		Integer pageNumber,
		String chunkText
) {

	/** 근거 버전이 남아 있을 때만 부른다. 가려진 근거는 응답에 넣지 않는다 */
	public static ChatSourceResponse from(MessageSource source) {
		return new ChatSourceResponse(
				source.getVersion().getDocument().getId(),
				source.getDocumentName(),
				source.getVersionNo(),
				source.getPageNumber(),
				source.getChunkText());
	}
}
