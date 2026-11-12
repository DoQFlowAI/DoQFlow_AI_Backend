package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import java.util.List;

/**
 * 문서 생성 + 초대 응답 [ documentId, currentVersionNo, status, invited, notFound ]  <API 7>
 * @param invited  PENDING으로 초대한 인원 수
 * @param notFound 가입되지 않은 이메일 목록 (오류가 아니라 응답으로 알려 준다)
 */
public record DocumentCreateResponse(
		Long documentId,
		int currentVersionNo,
		DocumentStatus status,
		int invited,
		List<String> notFound
) {

	// 생성된 문서와 초대 결과로 응답 생성
	public static DocumentCreateResponse of(Document document, int invited, List<String> notFound) {
		return new DocumentCreateResponse(
				document.getId(),
				document.getCurrentVersionNo(),
				document.getStatus(),
				invited,
				notFound);
	}
}
