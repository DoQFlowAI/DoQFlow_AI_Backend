package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import java.util.List;


/** 버전 일괄 업로드 응답 < API 18 >
 * 파일 저장, 행 생성 => 동기 처리 후, 바로 응답 / 추출, diff, 요약, 색인은 비동기로 진행
 * @param documentId
 * @param currentVersionNo
 * @param versions
 */
public record VersionUploadResponse(
		Long documentId,
		int currentVersionNo,
		List<UploadedVersion> versions
) {


	public record UploadedVersion(
			int versionNo,
			VersionStatus status
	) {

		public static UploadedVersion from(DocumentVersion version) {
			return new UploadedVersion(version.getVersionNo(), version.getStatus());
		}
	}

	public static VersionUploadResponse of(Document document, List<DocumentVersion> created) {
		return new VersionUploadResponse(
				document.getId(),
				document.getCurrentVersionNo(),
				created.stream().map(UploadedVersion::from).toList());
	}
}
