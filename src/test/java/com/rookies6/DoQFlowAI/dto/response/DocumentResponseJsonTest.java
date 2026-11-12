package com.rookies6.DoQFlowAI.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문서 응답의 JSON 모양 확인 < API 8, API 9, API 10 >
 *
 * <p>API 설계서 5.3~5.5의 예시와 필드 구성이 같은지 본다.
 * Spring Boot는 Jackson 3 기본값을 그대로 쓰므로 기본 JsonMapper 결과가 실제 응답과 같다.
 */
class DocumentResponseJsonTest {

	private static final Long OWNER_ID = 1L;
	private static final Instant UPDATED = Instant.parse("2026-11-06T09:30:00Z");

	private final JsonMapper mapper = JsonMapper.builder().build();
	private Document document;

	@BeforeEach
	void setUp() {
		AppUser owner = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		ReflectionTestUtils.setField(owner, "id", OWNER_ID);

		document = Document.builder().name("휴가 규정").uploadedBy(owner).build();
		ReflectionTestUtils.setField(document, "id", 7L);
		// DB에는 서버 시간대의 시각으로 들어 있다고 보고, 같은 순간을 서버 시간대로 넣는다.
		ReflectionTestUtils.setField(document, "updatedAt", LocalDateTime.ofInstant(UPDATED, ZoneId.systemDefault()));
	}

	@Test
	@DisplayName("목록 항목은 등록자 이름과 직급만 묶어서 내린다")
	void summaryNestsUploaderNameAndRankOnly() {
		String json = mapper.writeValueAsString(DocumentSummaryResponse.of(document, OWNER_ID));

		assertThat(json).contains("\"uploadedBy\":{\"name\":\"팀장\",\"rankLevel\":2}");
		assertThat(json).contains("\"owned\":true");
		assertThat(json).contains("\"updatedAt\":\"2026-11-06T09:30:00Z\"");
		assertThat(json).doesNotContain("email", "uploadedByName");
	}

	@Test
	@DisplayName("단건은 등록자 이메일까지 묶어서 내린다")
	void detailNestsUploaderIncludingEmail() {
		String json = mapper.writeValueAsString(DocumentDetailResponse.of(document, 99L, List.of()));

		assertThat(json).contains("\"uploadedBy\":{\"name\":\"팀장\",\"email\":\"lead@example.com\",\"rankLevel\":2}");
		assertThat(json).contains("\"owned\":false");
		assertThat(json).doesNotContain("uploadedByEmail", "storedFilename");
	}

	@Test
	@DisplayName("수정 응답의 시각은 ISO-8601 UTC다")
	void updateResponseTimeIsIsoUtc() {
		String json = mapper.writeValueAsString(DocumentUpdateResponse.from(document));

		assertThat(json).contains("\"updatedAt\":\"2026-11-06T09:30:00Z\"");
	}

	@Test
	@DisplayName("시각이 없으면 null")
	void nullTimeStaysNull() {
		assertThat(ApiTime.toInstant(null)).isNull();
	}
}
