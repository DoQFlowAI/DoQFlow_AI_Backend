package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * 변천사 타임라인 응답 (GET, /documents/{documentId}/history) < API 22 >
 */
public record HistoryResponse(
		Long documentId,
		List<VersionItem> versions,
		List<Segment> segments
) {
	public record VersionItem(
			int versionNo,
			String originalFilename,
			Instant createdAt
	) {

		public static VersionItem from(DocumentVersion version) {
			return new VersionItem(
					version.getVersionNo(),
					version.getOriginalFilename(),
					toInstant(version));
		}

		// DB의 DATETIME은 서버 시간대로 저장되므로, 서버 시간대 기준으로 UTC 시각(Instant)으로 바꾼다.
		private static Instant toInstant(DocumentVersion version) {
			return version.getCreatedAt() == null
					? null
					: version.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
		}
	}

	/**
	 * 인접 구간 하나의 변경 정보
	 *
	 * @param changeType    PROCEDURE / NUMERIC / WORDING, 요약 실패·생략 시 null
	 * @param summary       변경 요약, 요약 실패·생략 시 null
	 * @param changeCount   변경 블록 개수
	 * @param summaryStatus OK / FAILED / SKIPPED. FAILED면 화면은 changeCount만 표시
	 */
	public record Segment(
			int from,
			int to,
			String changeType,
			String summary,
			int changeCount,
			String summaryStatus
	) {
	}
}
