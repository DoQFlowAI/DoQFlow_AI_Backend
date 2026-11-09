package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import java.time.Instant;
import java.util.List;


/** 문서 단건 응답 (GET, /documents/{documentId})
 * [documentId, name, description, currentVersionNo, status, owned,
 * uploadedBy{name, email, rankLevel}, versions ]
 * <API 9>
 *
 * 등록자 정보는 읽기 권한자 전원에게 이름·이메일·직급을 모두 내려보낸다
 * 열람자 목록은 넣지 않는다 (API 13).
 */
public record DocumentDetailResponse(
		Long documentId,
		String name,
		String description,
		int currentVersionNo,
		DocumentStatus status,
		boolean owned,
		UploadedBy uploadedBy,
		List<VersionItem> versions
)

{
	// 단건 응답의 등록자 정보 { "name": "홍서연", "email": "hong@example.com", "rankLevel": 2 }
	public record UploadedBy(
			String name,
			String email,
			int rankLevel
	) {
	}

	//문서 단건 응답의 버전 항목,  storedFilename은 일부러 제외한다 (서버 저장 정보 노출 방지).
	public record VersionItem(
			int versionNo,
			String originalFilename,
			VersionStatus status,
			Instant createdAt
	) {

		public static VersionItem from(DocumentVersion version) {
			return new VersionItem(
					version.getVersionNo(),
					version.getOriginalFilename(),
					version.getStatus(),
					ApiTime.toInstant(version.getCreatedAt()));
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
				new UploadedBy(uploader.getName(), uploader.getEmail(), uploader.getRankLevel()),
				versions.stream().map(VersionItem::from).toList());
	}
}
