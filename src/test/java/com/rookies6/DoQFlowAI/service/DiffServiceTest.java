package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** [API-18 V-01] DiffService 단위 테스트 — DB·Spring 없이 실행된다. */
class DiffServiceTest {

	private final DiffService diffService = new DiffService(JsonMapper.builder().build());

	@Test
	@DisplayName("변경 추가 삭제를 블록으로 계산하고 같은 줄은 제외한다")
	void computesChangeInsertDeleteBlocksExcludingEqual() {
		String from = "승인자는 팀장이다.\n휴가는 15일이다.\n삭제될 문장.";
		String to = "승인자는 실장이다.\n휴가는 15일이다.\n새로 추가된 문장.\n하나 더 추가.";

		List<DiffBlock> blocks = diffService.diff(from, to);

		assertThat(blocks).extracting(DiffBlock::type).containsExactly("CHANGE", "CHANGE");
		assertThat(blocks.get(0)).isEqualTo(new DiffBlock("CHANGE", 0, 1, 0, 1));
		assertThat(blocks.get(1)).isEqualTo(new DiffBlock("CHANGE", 2, 1, 2, 2));
	}

	@Test
	@DisplayName("본문이 같으면 빈 목록이다")
	void sameTextGivesEmptyList() {
		assertThat(diffService.diff("a\nb", "a\nb")).isEmpty();
	}

	@Test
	@DisplayName("줄 추가와 삭제를 구분한다")
	void distinguishesInsertAndDelete() {
		assertThat(diffService.diff("a\nc", "a\nb\nc"))
				.containsExactly(new DiffBlock("INSERT", 1, 0, 1, 1));
		assertThat(diffService.diff("a\nb\nc", "a\nc"))
				.containsExactly(new DiffBlock("DELETE", 1, 1, 1, 0));
	}

	@Test
	@DisplayName("null 본문은 빈 본문으로 본다")
	void treatsNullAsEmpty() {
		assertThat(diffService.diff(null, "a"))
				.containsExactly(new DiffBlock("INSERT", 0, 0, 0, 1));
	}

	@Test
	@DisplayName("JSON으로 저장했다가 그대로 복원한다")
	void roundTripsThroughJson() {
		List<DiffBlock> blocks = diffService.diff("a\nb", "a\nc");

		String json = diffService.toJson(blocks);

		assertThat(diffService.fromJson(json)).isEqualTo(blocks);
	}

	@Test
	@DisplayName("diff_json이 없는 버전은 빈 목록으로 복원한다")
	void missingDiffJsonRestoresEmptyList() {
		assertThat(diffService.fromJson(null)).isEmpty();
		assertThat(diffService.fromJson(" ")).isEmpty();
	}
}
