package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** [API-18 V-01] DiffService 단위 테스트 — DB·Spring 없이 실행된다. */
class DiffServiceTest {

	private final DiffService diffService = new DiffService(JsonMapper.builder().build());

	@Test
	void 변경_추가_삭제를_블록으로_계산하고_같은_줄은_제외한다() {
		String from = "승인자는 팀장이다.\n휴가는 15일이다.\n삭제될 문장.";
		String to = "승인자는 실장이다.\n휴가는 15일이다.\n새로 추가된 문장.\n하나 더 추가.";

		List<DiffBlock> blocks = diffService.diff(from, to);

		assertThat(blocks).extracting(DiffBlock::type).containsExactly("CHANGE", "CHANGE");
		assertThat(blocks.get(0)).isEqualTo(new DiffBlock("CHANGE", 0, 1, 0, 1));
		assertThat(blocks.get(1)).isEqualTo(new DiffBlock("CHANGE", 2, 1, 2, 2));
	}

	@Test
	void 본문이_같으면_빈_목록이다() {
		assertThat(diffService.diff("a\nb", "a\nb")).isEmpty();
	}

	@Test
	void 줄_추가와_삭제를_구분한다() {
		assertThat(diffService.diff("a\nc", "a\nb\nc"))
				.containsExactly(new DiffBlock("INSERT", 1, 0, 1, 1));
		assertThat(diffService.diff("a\nb\nc", "a\nc"))
				.containsExactly(new DiffBlock("DELETE", 1, 1, 1, 0));
	}

	@Test
	void null_본문은_빈_본문으로_본다() {
		assertThat(diffService.diff(null, "a"))
				.containsExactly(new DiffBlock("INSERT", 0, 0, 0, 1));
	}

	@Test
	void JSON으로_저장했다가_그대로_복원한다() {
		List<DiffBlock> blocks = diffService.diff("a\nb", "a\nc");

		String json = diffService.toJson(blocks);

		assertThat(diffService.fromJson(json)).isEqualTo(blocks);
	}

	@Test
	void diff_json이_없는_버전은_빈_목록으로_복원한다() {
		assertThat(diffService.fromJson(null)).isEmpty();
		assertThat(diffService.fromJson(" ")).isEmpty();
	}
}
