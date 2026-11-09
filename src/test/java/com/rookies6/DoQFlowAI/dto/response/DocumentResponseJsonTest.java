package com.rookies6.DoQFlowAI.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
	void 목록_항목은_등록자_이름과_직급만_묶어서_내린다() {
		String json = mapper.writeValueAsString(DocumentSummaryResponse.of(document, OWNER_ID));

		assertThat(json).contains("\"uploadedBy\":{\"name\":\"팀장\",\"rankLevel\":2}");
		assertThat(json).contains("\"owned\":true");
		assertThat(json).contains("\"updatedAt\":\"2026-11-06T09:30:00Z\"");
		assertThat(json).doesNotContain("email", "uploadedByName");
	}

	@Test
	void 단건은_등록자_이메일까지_묶어서_내린다() {
		String json = mapper.writeValueAsString(DocumentDetailResponse.of(document, 99L, List.of()));

		assertThat(json).contains("\"uploadedBy\":{\"name\":\"팀장\",\"email\":\"lead@example.com\",\"rankLevel\":2}");
		assertThat(json).contains("\"owned\":false");
		assertThat(json).doesNotContain("uploadedByEmail", "storedFilename");
	}

	@Test
	void 수정_응답의_시각은_ISO_8601_UTC다() {
		String json = mapper.writeValueAsString(DocumentUpdateResponse.from(document));

		assertThat(json).contains("\"updatedAt\":\"2026-11-06T09:30:00Z\"");
	}

	@Test
	void 시각이_없으면_null() {
		assertThat(ApiTime.toInstant(null)).isNull();
	}
}
