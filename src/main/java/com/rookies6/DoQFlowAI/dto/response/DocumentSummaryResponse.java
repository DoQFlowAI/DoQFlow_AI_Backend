package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import java.time.LocalDateTime;


/** 문서 이름/설명 수정 요청
 * [ documentId, name, description, currentVersionNo, status
 * owned, uploadedByName, uploadedByRankLevel, updatedAt]
 * <API 8>
 */
public record DocumentSummaryResponse(
		Long documentId,
		String name,
		String description,
		int currentVersionNo,
		DocumentStatus status,
		boolean owned,
		String uploadedByName,
		int uploadedByRankLevel,
		LocalDateTime updatedAt)
{
	// 엔티티를 목록 항목으로 변환, 요청한 사용자의 내부 ID. 등록자와 같으면 owned = true
	public static DocumentSummaryResponse of(Document document, Long meId) {
		AppUser uploader = document.getUploadedBy();
		return new DocumentSummaryResponse(
				document.getId(),
				document.getName(),
				document.getDescription(),
				document.getCurrentVersionNo(),
				document.getStatus(),
				uploader.getId().equals(meId),
				uploader.getName(),
				uploader.getRankLevel(),
				document.getUpdatedAt());
	}
}
