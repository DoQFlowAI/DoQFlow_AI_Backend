package com.rookies6.DoQFlowAI.dto.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.service.DiffBlock;
import com.rookies6.DoQFlowAI.service.DiffService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** /internal/history 변경 블록 텍스트 만들기 단위 테스트  */
class AiHistoryBlockTest {

	private final DiffService diffService = new DiffService(JsonMapper.builder().build());

	@Test
	@DisplayName("DiffBlock의 줄 위치로 변경 전후 줄을 잘라낸다")
	void slicesBeforeAndAfterLinesByDiffBlockPosition() {
		String from = "1. 목적\n결재는 팀장이 승인한다.\n끝";
		String to = "1. 목적\n결재는 실장이 승인한다.\n승인 기한은 3일이다.\n끝";

		List<String> fromLines = DiffService.lines(from);
		List<String> toLines = DiffService.lines(to);
		List<AiHistory.Block> blocks = diffService.diff(from, to).stream()
				.map(block -> AiHistory.Block.of(block, fromLines, toLines))
				.toList();

		assertThat(blocks).containsExactly(new AiHistory.Block(
				"CHANGE",
				List.of("결재는 팀장이 승인한다."),
				List.of("결재는 실장이 승인한다.", "승인 기한은 3일이다.")));
	}

	@Test
	@DisplayName("INSERT는 before가 DELETE는 after가 빈 배열")
	void insertHasEmptyBeforeAndDeleteHasEmptyAfter() {
		List<String> lines = List.of("a", "b");

		AiHistory.Block insert = AiHistory.Block.of(new DiffBlock("INSERT", 1, 0, 1, 1), List.of("a"), lines);
		AiHistory.Block delete = AiHistory.Block.of(new DiffBlock("DELETE", 1, 1, 1, 0), lines, List.of("a"));

		assertThat(insert).isEqualTo(new AiHistory.Block("INSERT", List.of(), List.of("b")));
		assertThat(delete).isEqualTo(new AiHistory.Block("DELETE", List.of("b"), List.of()));
	}
}
