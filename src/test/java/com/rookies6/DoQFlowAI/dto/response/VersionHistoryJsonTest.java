package com.rookies6.DoQFlowAI.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.service.DiffBlock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 변천사·비교 응답의 JSON 모양 확인 < API 22, API 24 >
 *
 * <p>Spring Boot는 Jackson 3 기본값을 그대로 쓰므로(spring.jackson.use-jackson2-defaults=false),
 * 기본 JsonMapper로 직렬화한 결과가 실제 응답과 같다.
 */
class VersionHistoryJsonTest {

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	@DisplayName("시각은 ISO-8601 UTC 문자열로 나간다")
	void serializesTimeAsIsoUtcString() {
		HistoryResponse.VersionItem item = new HistoryResponse.VersionItem(
				1, "v1.pdf", Instant.parse("2026-11-06T09:30:00Z"));

		assertThat(mapper.writeValueAsString(item)).contains("\"createdAt\":\"2026-11-06T09:30:00Z\"");
	}

	@Test
	@DisplayName("비교 응답의 변경 블록 필드명은 diffJson이다")
	void compareResponseNamesBlocksDiffJson() {
		CompareResponse response = new CompareResponse(1, 4, "a", "b", 1,
				List.of(new DiffBlock("CHANGE", 0, 1, 0, 1)));

		String json = mapper.writeValueAsString(response);

		assertThat(json).contains("\"diffJson\":[{\"type\":\"CHANGE\",\"fromLine\":0,\"fromCount\":1,\"toLine\":0,\"toCount\":1}]");
		assertThat(json).doesNotContain("blocks");
	}
}
