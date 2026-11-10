package com.rookies6.DoQFlowAI.dto.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.service.DiffBlock;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * /internal/history 요청 크기 제한 단위 테스트
 * AI Module 상한(구간당 블록 500, 한쪽 200줄, 한 줄 2000자) =>  400, FAILED
 */
class AiHistoryLimitTest {

	@Test
	@DisplayName("한쪽이 200줄을 넘으면 앞 200줄만 보낸다")
	void keepsFirst200LinesPerSide() {
		List<String> from = IntStream.range(0, 300).mapToObj(i -> "줄" + i).toList();
		List<String> to = List.of("새 줄");

		AiHistory.Block block = AiHistory.Block.of(new DiffBlock("CHANGE", 0, 300, 0, 1), from, to);

		assertThat(block.before()).hasSize(AiHistory.MAX_LINES_PER_SIDE);
		assertThat(block.before().get(0)).isEqualTo("줄0");
		assertThat(block.before().get(199)).isEqualTo("줄199");
		assertThat(block.after()).containsExactly("새 줄");
	}

	@Test
	@DisplayName("한 줄이 2000자를 넘으면 앞 2000자만 보낸다")
	void truncatesLineTo2000Chars() {
		String longLine = "가".repeat(2500);

		AiHistory.Block block = AiHistory.Block.of(new DiffBlock("INSERT", 0, 0, 0, 1), List.of(), List.of(longLine));

		assertThat(block.after().get(0)).hasSize(AiHistory.MAX_LINE_LENGTH);
	}

	@Test
	@DisplayName("서로게이트 쌍을 반으로 자르지 않는다")
	void doesNotSplitSurrogatePair() {
		String smile = new String(Character.toChars(0x1F600)); // UTF-16으로 2칸인 글자
		String line = "가".repeat(1999) + smile + "끝";

		AiHistory.Block block = AiHistory.Block.of(new DiffBlock("INSERT", 0, 0, 0, 1), List.of(), List.of(line));

		String sent = block.after().get(0);
		assertThat(sent.codePointCount(0, sent.length())).isEqualTo(AiHistory.MAX_LINE_LENGTH);
		assertThat(sent).endsWith(smile);
	}

	@Test
	@DisplayName("상한 안이면 그대로 보낸다")
	void keepsBlockWithinLimits() {
		List<String> lines = List.of("a", "b");

		AiHistory.Block block = AiHistory.Block.of(new DiffBlock("CHANGE", 0, 2, 0, 2), lines, lines);

		assertThat(block.before()).containsExactly("a", "b");
		assertThat(block.after()).containsExactly("a", "b");
	}

	@Test
	@DisplayName("구간의 블록이 500개를 넘으면 앞 500개만 보낸다")
	void keepsFirst500BlocksPerSegment() {
		AiHistory.Block block = new AiHistory.Block("INSERT", List.of(), List.of("x"));
		List<AiHistory.Block> blocks = Collections.nCopies(600, block);

		AiHistory.RequestSegment segment = AiHistory.RequestSegment.of(1, 2, blocks);

		assertThat(segment.blocks()).hasSize(AiHistory.MAX_BLOCKS_PER_SEGMENT);
		assertThat(AiHistory.RequestSegment.of(1, 2, List.of(block)).blocks()).hasSize(1);
	}
}
