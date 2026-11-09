package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.Document;
import java.time.LocalDateTime;


// 문서 수정 응답 [ documentId, name, description, updatedAt ]  <API 10>
public record DocumentUpdateResponse(
		Long documentId,
		String name,
		String description,
		LocalDateTime updatedAt
) {

	/**
	 * 수정된 문서로 응답생성, updatedAt은 @PreUpdate으로 갱신,
	 * 서비스에서 flush한 뒤 호출해야 새 시각이 담긴다.
	 */
	public static DocumentUpdateResponse from(Document document) {
		return new DocumentUpdateResponse(
				document.getId(),
				document.getName(),
				document.getDescription(),
				document.getUpdatedAt());
	}
}
