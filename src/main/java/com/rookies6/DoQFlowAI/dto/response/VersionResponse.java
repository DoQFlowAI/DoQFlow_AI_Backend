package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import java.time.Instant;

/**
 * 버전 목록 응답 항목 (GET, /documents/{documentId}/versions) < API 19 >. 단건(API 20)은 본문을 더한 VersionDetailResponse
 *
 * <pre>
 * { "versionNo": 1, "originalFilename": "v1.pdf", "fileSize": 184320,
 *   "status": "READY", "changeType": null, "changeSummary": null, "createdAt": "2026-11-06T09:30:00Z" }
 * </pre>
 *
 * storedFilename과 저장 경로는 넣지 않는다. 파일 자체를 내려받는 API는 없고, 본문은 단건(API 20)이나 원문 보기(API 23)로 조회한다.
 *
 * @param changeType    직전 버전 대비 PROCEDURE / NUMERIC / WORDING. v1·요약 실패·변경 없음이면 null
 * @param changeSummary 직전 버전 대비 변경 요약. v1·요약 실패·변경 없음이면 null
 * @param createdAt     업로드 일시 (ISO-8601 UTC)
 */
public record VersionResponse(
		int versionNo,
		String originalFilename,
		long fileSize,
		VersionStatus status,
		String changeType,
		String changeSummary,
		Instant createdAt
) {

	public static VersionResponse from(DocumentVersion version) {
		return new VersionResponse(
				version.getVersionNo(),
				version.getOriginalFilename(),
				version.getFileSize(),
				version.getStatus(),
				version.getChangeType(),
				version.getChangeSummary(),
				ApiTime.toInstant(version.getCreatedAt()));
	}
}
