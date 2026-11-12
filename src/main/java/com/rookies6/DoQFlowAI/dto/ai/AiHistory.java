package com.rookies6.DoQFlowAI.dto.ai;

import com.rookies6.DoQFlowAI.service.DiffBlock;
import java.util.List;

/** 요청·응답 ( POST /internal/history )
 *
 * Backend가 java-diff-utils로 계산한 변경 블록의 텍스트만 보낸다. 본문 전체는 보내지 않는다.
 * 변경 블록이 없는 구간은 보내지 않고 Backend가 SKIPPED로 저장한다.
 */
public final class AiHistory {

	private AiHistory() {
	}

	/**
	 * @param documentId 문서 ID
	 * @param segments   요약할 인접 구간. ( 보내 읽기 제한(180초), 후처리에서는 구간 하나씩 전송 )
	 */
	public record Request(
			Long documentId,
			List<RequestSegment> segments
	) {
	}

	/*
	 * AI Module 요청 검증 상한 (DoQScale_LLM2 app/schemas/history.py). 넘으면 400이 나서 그 구간 요약이 FAILED가 된다.
	 * 넘는 부분은 잘라서 보낸다. 요약에 쓰는 입력만 줄어들 뿐, 저장하는 diff_json·changeCount는 그대로다.
	 */
	public static final int MAX_BLOCKS_PER_SEGMENT = 500;
	public static final int MAX_LINES_PER_SIDE = 200;
	public static final int MAX_LINE_LENGTH = 2000; // 글자 수 (Python str 길이 = 코드 포인트)

	/**
	 * 요청의 인접 구간 하나
	 *
	 * @param from   이전 버전 번호
	 * @param to     다음 버전 번호
	 * @param blocks 이 구간의 변경 블록 (EQUAL 제외). 최대 500개
	 */
	public record RequestSegment(
			int from,
			int to,
			List<Block> blocks
	) {

		/** 블록이 500개를 넘으면 앞에서부터 500개만 보낸다 */
		public static RequestSegment of(int from, int to, List<Block> blocks) {
			List<Block> limited = blocks.size() > MAX_BLOCKS_PER_SEGMENT
					? blocks.subList(0, MAX_BLOCKS_PER_SEGMENT)
					: blocks;
			return new RequestSegment(from, to, List.copyOf(limited));
		}
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
		 * AI Module 상한에 맞춰 한쪽당 앞 200줄, 한 줄당 앞 2000자만 담는다.
		 */
		public static Block of(DiffBlock block, List<String> fromLines, List<String> toLines) {
			return new Block(
					block.type(),
					limit(fromLines.subList(block.fromLine(), block.fromLine() + block.fromCount())),
					limit(toLines.subList(block.toLine(), block.toLine() + block.toCount())));
		}

		private static List<String> limit(List<String> lines) {
			return lines.stream()
					.limit(MAX_LINES_PER_SIDE)
					.map(Block::limitLength)
					.toList();
		}

		// 서로게이트 쌍(이모지 등)을 반으로 자르지 않도록 코드 포인트 기준으로 자른다
		private static String limitLength(String line) {
			if (line.codePointCount(0, line.length()) <= MAX_LINE_LENGTH) {
				return line;
			}
			return line.substring(0, line.offsetByCodePoints(0, MAX_LINE_LENGTH));
		}
	}

	/// @param segments 인접 구간별 요약 결과. 한 구간이 실패해도 나머지는 돌아온다
	public record Response(
			List<Segment> segments
	) {
	}

	/**
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
