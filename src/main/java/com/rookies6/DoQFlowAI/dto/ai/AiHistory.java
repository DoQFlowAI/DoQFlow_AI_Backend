package com.rookies6.DoQFlowAI.dto.ai;

import com.rookies6.DoQFlowAI.service.DiffBlock;
import java.util.List;

/**
 * POST /internal/history 요청·응답
 *
 * Backend가 java-diff-utils로 계산한 변경 블록의 텍스트만 보낸다. 본문 전체는 보내지 않는다.
 * 변경 블록이 없는 구간은 보내지 않고 Backend가 SKIPPED로 저장한다.
 */
public final class AiHistory {

	private AiHistory() {
	}

	/**
	 * @param documentId 문서 ID
	 * @param segments   요약할 인접 구간. 후처리에서는 구간 하나씩 보내 읽기 제한(180초)을 구간 단위로 맞춘다.
	 */
	public record Request(
			Long documentId,
			List<RequestSegment> segments
	) {
	}

	/**
	 * 요청의 인접 구간 하나
	 *
	 * @param from   이전 버전 번호
	 * @param to     다음 버전 번호
	 * @param blocks 이 구간의 변경 블록 (EQUAL 제외)
	 */
	public record RequestSegment(
			int from,
			int to,
			List<Block> blocks
	) {
	}

	/**
	 * 변경 블록 하나의 텍스트
	 *
	 * @param type   INSERT / DELETE / CHANGE
	 * @param before 이전 버전의 해당 줄들 (INSERT면 빈 배열)
	 * @param after  다음 버전의 해당 줄들 (DELETE면 빈 배열)
	 */
	public record Block(
			String type,
			List<String> before,
			List<String> after
	) {

		/**
		 * DiffBlock의 줄 위치로 두 본문에서 해당 줄을 잘라 블록을 만든다.
		 * fromLines·toLines는 diff를 계산할 때와 같은 방식(DiffService.lines)으로 나눈 줄이어야 한다.
		 */
		public static Block of(DiffBlock block, List<String> fromLines, List<String> toLines) {
			return new Block(
					block.type(),
					List.copyOf(fromLines.subList(block.fromLine(), block.fromLine() + block.fromCount())),
					List.copyOf(toLines.subList(block.toLine(), block.toLine() + block.toCount())));
		}
	}

	/** @param segments 인접 구간별 요약 결과. 한 구간이 실패해도 나머지는 돌아온다 */
	public record Response(
			List<Segment> segments
	) {
	}

	/**
	 * 응답의 인접 구간 하나의 요약
	 *
	 * @param from          이전 버전 번호
	 * @param to            다음 버전 번호
	 * @param changeType    PROCEDURE / NUMERIC / WORDING (DB 컬럼 12자). 실패 시 null
	 * @param summary       변경 요약 문장. 실패 시 null
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
