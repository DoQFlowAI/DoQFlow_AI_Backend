package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import java.time.LocalDateTime;
import java.util.List;


/** 문서 단건 응답 (GET, /documents/{documentId})
 * [documentId, name, description, currentVersionNo, status, owned,
 * uploadedByName, uploadedByEmail, uploadedByRankLevel, versions ]
 * <API 9>
 */
public record DocumentDetailResponse(
		Long documentId,
		String name,
		String description,
		int currentVersionNo,
		DocumentStatus status,
		boolean owned,
		String uploadedByName,
		String uploadedByEmail,
		int uploadedByRankLevel,
		List<VersionItem> versions
)

{
	//문서 단건 응답의 버전 항목,  storedFilename은 일부러 제외한다 (서버 저장 정보 노출 방지).
	public record VersionItem(
			int versionNo,
			String originalFilename,
			VersionStatus status,
			LocalDateTime createdAt
	) {

		public static VersionItem from(DocumentVersion version) {
			return new VersionItem(
					version.getVersionNo(),
					version.getOriginalFilename(),
					version.getStatus(),
					version.getCreatedAt());
		}
	}


	// 문서와 버전 목록을 이용하여 응답을 생성한다.
	public static DocumentDetailResponse of(Document document, Long meId, List<DocumentVersion> versions) {
		AppUser uploader = document.getUploadedBy();
		return new DocumentDetailResponse(
				document.getId(),
				document.getName(),
				document.getDescription(),
				document.getCurrentVersionNo(),
				document.getStatus(),
				uploader.getId().equals(meId),
				uploader.getName(),
				uploader.getEmail(),
				uploader.getRankLevel(),
				versions.stream().map(VersionItem::from).toList());
	}
}
