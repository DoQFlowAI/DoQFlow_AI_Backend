package com.rookies6.DoQFlowAI.dto.ai;

import java.util.List;

public final class AiHistory {

	private AiHistory() {
	}

	/**
	 * @param documentId 문서 ID
	 * @param versionIds 요약할 버전 ID 목록 (버전 순서)
	 */
	public record Request(
			Long documentId,
			List<Long> versionIds
	) {
	}

	/** @param segments 인접 구간별 요약 결과 */
	public record Response(
			List<Segment> segments
	) {
	}

	/**
	 * 인접 구간 하나의 요약
	 *
	 * @param from          이전 버전 번호
	 * @param to            다음 버전 번호
	 * @param changeType    PROCEDURE / NUMERIC / WORDING (DB 컬럼 12자)
	 * @param summary       변경 요약 문장
	 * @param summaryStatus OK / FAILED. FAILED면 화면은 changeCount만 표시
	 */
	public record Segment(
			int from,
			int to,
			String changeType,
			String summary,
			String summaryStatus
	) {
	}
}
