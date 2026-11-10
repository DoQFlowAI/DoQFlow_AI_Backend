package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.service.Utf8Truncator;
import java.time.Instant;

/**
 * 버전 단건 응답 (GET, /documents/{documentId}/versions/{no}) < API 20 >
 *
 * <pre>
 * { "versionNo": 2, "originalFilename": "v2.pdf", "fileSize": 184320, "status": "READY",
 *   "changeType": "PROCEDURE", "changeSummary": "...", "createdAt": "2026-11-06T09:30:00Z",
 *   "contentText": "...", "contentTruncated": false }
 * </pre>
 *
 * 목록 항목(VersionResponse)에 본문을 더한 것이다. 화면의 버전 본문 패널이 이 응답 하나로 정보와 본문을 함께 보여 준다
 * (Frontend 개발 명세서 4장). 목록에는 본문을 넣지 않는다 (버전마다 최대 1MB라 목록이 무거워진다).
 *
 * @param contentText      DB의 content_text. 원문 보기(API 23)와 같은 규칙: READY가 아니면 null, 상한(1MB)에서 잘린다
 * @param contentTruncated 상한에서 잘렸으면 true
 */
public record VersionDetailResponse(
		int versionNo,
		String originalFilename,
		long fileSize,
		VersionStatus status,
		String changeType,
		String changeSummary,
		Instant createdAt,
		String contentText,
		boolean contentTruncated
) {

	public static VersionDetailResponse of(DocumentVersion version, int maxBytes) {
		String text = version.getStatus() == VersionStatus.READY ? version.getContentText() : null;
		Utf8Truncator.Result body = Utf8Truncator.truncate(text, maxBytes);
		return new VersionDetailResponse(
				version.getVersionNo(),
				version.getOriginalFilename(),
				version.getFileSize(),
				version.getStatus(),
				version.getChangeType(),
				version.getChangeSummary(),
				ApiTime.toInstant(version.getCreatedAt()),
				body.content(),
				body.truncated());
	}
}
