package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.JwtProvider;
import com.rookies6.DoQFlowAI.service.AiClientService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문서 생성 + 초대 통합 테스트 < API 7 >
 *
 * 실제 MariaDB, 실제 보안 필터(JWT), 실제 DocumentService·PermissionService·AuditService로 HTTP 요청을 보낸다.
 * 확인하는 것 — 201 응답 형식, 사원 403, 요청 검증 400, 초대 결과(미가입 notFound, 중복·등록자 자신 제외),
 * 초대받은 사람은 수락 전에는 문서를 볼 수 없음, 감사 로그 기록.
 *
 * DB가 있을 때만 돈다 (다른 통합 테스트와 같은 환경변수).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class DocumentCreateIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> System.getenv("DOQ_IT_DB_URL"));
		registry.add("spring.datasource.username", () -> System.getenv("DOQ_IT_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("DOQ_IT_DB_PASSWORD"));
		// 테스트 전용 값. 실제 비밀값을 쓰지 않는다
		registry.add("jwt.secret", () -> "integration-test-secret-integration-test-secret");
		registry.add("ai.internal-key", () -> "integration-test-key");
	}

	@MockitoBean
	AiClientService ai;

	@Autowired MockMvc mvc;
	@Autowired JwtProvider jwt;
	@Autowired TransactionTemplate tx;
	@Autowired EntityManager em;

	private String leadEmail;
	private String staffEmail;
	private Long staffId;
	private String leadToken;
	private String staffToken;

	@BeforeEach
	void setUp() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		leadEmail = "lead-" + suffix + "@example.com";
		staffEmail = "staff-" + suffix + "@example.com";
		AppUser[] users = tx.execute(status -> {
			AppUser lead = user(leadEmail, 2);
			AppUser staff = user(staffEmail, 1);
			return new AppUser[] {lead, staff};
		});
		staffId = users[1].getId();
		leadToken = "Bearer " + jwt.createAccessToken(users[0].getPublicId());
		staffToken = "Bearer " + jwt.createAccessToken(users[1].getPublicId());
	}

	private AppUser user(String email, int rankLevel) {
		AppUser user = AppUser.builder().email(email).passwordHash("x")
				.name(rankLevel == 2 ? "팀장" : "사원").rankLevel(rankLevel).build();
		em.persist(user);
		return user;
	}

	private MvcResult create(String token, Object body) throws Exception {
		return mvc.perform(post("/documents").header(HttpHeaders.AUTHORIZATION, token)
						.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)))
				.andReturn();
	}

	private long countAudit(String eventType, Long documentId) {
		return ((Number) em.createNativeQuery(
						"select count(*) from audit_log where event_type = ?1 and target_id = ?2")
				.setParameter(1, eventType).setParameter(2, documentId).getSingleResult()).longValue();
	}

	@Test
	@DisplayName("팀장이 만들면 201이고 가입한 사람만 PENDING으로 초대하고 미가입 이메일은 notFound로 알려 준다")
	void leadCreatesDocumentAndInvites() throws Exception {
		String unknown = "nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		MvcResult result = create(leadToken, Map.of(
				"name", "출장비 규정",
				"description", "정산 기준",
				// 같은 사람 대소문자 중복, 등록자 자신, 미가입 이메일
				"inviteeEmails", List.of(staffEmail, staffEmail.toUpperCase(), leadEmail, unknown)));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		var data = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
		long documentId = data.get("documentId").asLong();
		assertThat(data.get("currentVersionNo").asInt()).isZero();
		assertThat(data.get("status").asString()).isEqualTo("EMPTY");
		assertThat(data.get("invited").asInt()).isEqualTo(1);
		assertThat(data.get("notFound")).hasSize(1);
		assertThat(data.get("notFound").get(0).asString()).isEqualTo(unknown);

		List<?> rows = em.createNativeQuery(
						"select user_id, status from document_permission where document_id = ?1")
				.setParameter(1, documentId).getResultList();
		assertThat(rows).hasSize(1);
		Object[] row = (Object[]) rows.get(0);
		assertThat(((Number) row[0]).longValue()).isEqualTo(staffId);
		assertThat(row[1]).isEqualTo("PENDING");

		assertThat(countAudit("DOCUMENT_CREATE", documentId)).isEqualTo(1);
		assertThat(countAudit("PERMISSION_GRANT", documentId)).isEqualTo(1);

		// 등록자에게는 목록에 내 문서로 보인다
		mvc.perform(get("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.owned").value(true));
		// 초대받은 사원은 수락 전(PENDING)이라 아직 볼 수 없다
		mvc.perform(get("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, staffToken))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("사원이 만들면 403이고 문서가 생기지 않는다")
	void staffCannotCreate() throws Exception {
		long before = ((Number) em.createNativeQuery("select count(*) from document").getSingleResult()).longValue();

		MvcResult result = create(staffToken, Map.of("name", "사원 문서"));

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.get("error").get("code").asString()).isEqualTo("FORBIDDEN");
		long after = ((Number) em.createNativeQuery("select count(*) from document").getSingleResult()).longValue();
		assertThat(after).isEqualTo(before);
	}

	@Test
	@DisplayName("이름이 비었거나 초대 이메일이 50개를 넘거나 형식이 틀리면 400")
	void invalidRequestIs400() throws Exception {
		assertThat(create(leadToken, Map.of("name", " ")).getResponse().getStatus()).isEqualTo(400);
		assertThat(create(leadToken, Map.of("name", "가".repeat(201))).getResponse().getStatus()).isEqualTo(400);
		List<String> tooMany = IntStream.range(0, 51).mapToObj(i -> "u" + i + "@example.com").toList();
		assertThat(create(leadToken, Map.of("name", "문서", "inviteeEmails", tooMany)).getResponse().getStatus())
				.isEqualTo(400);
		assertThat(create(leadToken, Map.of("name", "문서", "inviteeEmails", List.of("not-an-email")))
				.getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	@DisplayName("토큰이 없으면 401")
	void createWithoutTokenIs401() throws Exception {
		mvc.perform(post("/documents").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"문서\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("문서 수정은 바뀐 필드 이름으로 감사 로그를 남긴다")
	void updateLeavesAudit() throws Exception {
		MvcResult created = create(leadToken, Map.of("name", "이전 이름"));
		long documentId = JSON.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.get("data").get("documentId").asLong();

		mvc.perform(patch("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, leadToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"새 이름\"}"))
				.andExpect(status().isOk());

		assertThat(countAudit("DOCUMENT_UPDATE", documentId)).isEqualTo(1);
	}
}
