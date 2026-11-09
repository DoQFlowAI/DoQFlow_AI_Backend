package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import java.time.Instant;


/** 문서 목록 항목 (GET, /documents)
 * [ documentId, name, description, currentVersionNo, status,
 * owned, updatedAt, uploadedBy{name, rankLevel} ]
 * <API 8>
 *
 * 목록에는 등록자의 이름과 직급만 넣어 응답 크기를 줄인다. 이메일은 단건 <API 9>에서만 내려간다.
 */
public record DocumentSummaryResponse(
		Long documentId,
		String name,
		String description,
		int currentVersionNo,
		DocumentStatus status,
		boolean owned,
		Instant updatedAt,
		UploadedBy uploadedBy)
{
	// 목록 항목의 등록자 정보 { "name": "홍서연", "rankLevel": 2 }
	public record UploadedBy(
			String name,
			int rankLevel
	) {
	}

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
				ApiTime.toInstant(document.getUpdatedAt()),
				new UploadedBy(uploader.getName(), uploader.getRankLevel()));
	}
}
