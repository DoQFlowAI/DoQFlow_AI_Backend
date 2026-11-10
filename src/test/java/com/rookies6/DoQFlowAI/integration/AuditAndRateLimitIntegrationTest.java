package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.security.JwtProvider;
import com.rookies6.DoQFlowAI.service.AiClientService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 버전·질의응답 감사 로그와 호출 횟수 제한 통합 테스트 < API 18, 21, 27, P-1 >
 *
 * 실제 MariaDB, 실제 보안 필터(JWT), 실제 서비스·AuditService·RateLimiter로 HTTP 요청을 보낸다. AI Module만 가짜다.
 * 확인하는 것 — audit_log에 남는 이벤트와 내용(파일명·질문 원문 없음), 질문 21번째·조회 31번째 429.
 *
 * DB가 있을 때만 돈다 (다른 통합 테스트와 같은 환경변수).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class AuditAndRateLimitIntegrationTest {

	private static final Path UPLOAD_DIR = createTempDir();
	private static final Duration WAIT = Duration.ofSeconds(15);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> System.getenv("DOQ_IT_DB_URL"));
		registry.add("spring.datasource.username", () -> System.getenv("DOQ_IT_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("DOQ_IT_DB_PASSWORD"));
		registry.add("app.upload-dir", UPLOAD_DIR::toString);
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
	@Autowired DocumentRepository documents;

	private String leadToken;
	private String outsiderToken;
	private Long outsiderId;
	private Long documentId;

	@BeforeEach
	void setUp() {
		reset(ai);
		when(ai.history(anyLong(), anyList())).thenAnswer(invocation -> {
			List<AiHistory.RequestSegment> segments = invocation.getArgument(1);
			return new AiHistory.Response(segments.stream()
					.map(s -> new AiHistory.Segment(s.from(), s.to(), "PROCEDURE", "요약", "OK")).toList());
		});
		when(ai.index(anyLong(), anyLong(), anyInt())).thenReturn(new AiIndex.Response("READY", 1));

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		AppUser[] users = tx.execute(status -> {
			AppUser lead = user("lead-" + suffix, 2);
			AppUser outsider = user("outsider-" + suffix, 1);
			Document document = Document.builder().name("감사 테스트").uploadedBy(lead).build();
			em.persist(document);
			documentId = document.getId();
			return new AppUser[] {lead, outsider};
		});
		outsiderId = users[1].getId();
		leadToken = "Bearer " + jwt.createAccessToken(users[0].getPublicId());
		outsiderToken = "Bearer " + jwt.createAccessToken(users[1].getPublicId());
	}

	private AppUser user(String prefix, int rankLevel) {
		AppUser user = AppUser.builder().email(prefix + "@example.com").passwordHash("x")
				.name(rankLevel == 2 ? "팀장" : "사원").rankLevel(rankLevel).build();
		em.persist(user);
		return user;
	}

	private static MockMultipartFile text(String name, String content) {
		return new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	private List<Object[]> auditsFor(String eventType, String targetType, Long targetId) {
		return em.createNativeQuery("select target_type, target_id, detail_json from audit_log"
						+ " where event_type = ?1 and target_type = ?2 and target_id = ?3 order by id")
				.setParameter(1, eventType).setParameter(2, targetType).setParameter(3, targetId)
				.getResultList();
	}

	@Test
	@DisplayName("업로드 성공·거부와 버전 삭제가 감사 로그에 남고 원본 파일명은 남지 않는다")
	void versionUploadAndDeleteAreAudited() throws Exception {
		mvc.perform(multipart("/documents/{id}/versions", documentId)
						.file(text("v1.txt", "1. 목적\n결재는 팀장이 승인한다."))
						.file(text("v2.txt", "1. 목적\n결재는 실장이 승인한다."))
						.header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isCreated());
		waitUntil(() -> documents.findById(documentId).orElseThrow().getStatus() == DocumentStatus.READY);

		List<Object[]> uploaded = auditsFor("VERSION_UPLOAD", "DOCUMENT", documentId);
		assertThat(uploaded).hasSize(1);
		assertThat(uploaded.get(0)[2].toString()).contains("fileCount=2").contains("versionNos=[1, 2]");

		// 확장자 위조(.exe), 등록자가 아닌 사람의 업로드
		mvc.perform(multipart("/documents/{id}/versions", documentId)
						.file(new MockMultipartFile("files", "기밀_위조.exe", "application/octet-stream", new byte[] {1, 2}))
						.header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/documents/{id}/versions", documentId).file(text("v3.txt", "새 본문"))
						.header(HttpHeaders.AUTHORIZATION, outsiderToken))
				.andExpect(status().isNotFound());

		List<Object[]> failed = auditsFor("VERSION_UPLOAD_FAILED", "DOCUMENT", documentId);
		assertThat(failed).hasSize(1);
		assertThat(failed.get(0)[2].toString()).contains("reason=INVALID_TYPE").doesNotContain("기밀", ".exe");
		// 못 읽는 문서(404)는 대상 문서를 비워 둔다
		List<?> notFound = em.createNativeQuery("select detail_json from audit_log"
						+ " where event_type = 'VERSION_UPLOAD_FAILED' and actor_user_id = ?1 and target_id is null")
				.setParameter(1, outsiderId).getResultList();
		assertThat(notFound).hasSize(1);
		assertThat(notFound.get(0).toString()).contains("reason=NOT_FOUND");

		Long v1Id = ((Number) em.createNativeQuery(
						"select id from document_version where document_id = ?1 and version_no = 1")
				.setParameter(1, documentId).getSingleResult()).longValue();
		mvc.perform(delete("/documents/{id}/versions/1", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isNoContent());

		List<Object[]> deleted = auditsFor("VERSION_DELETE", "DOCUMENT_VERSION", v1Id);
		assertThat(deleted).hasSize(1);
		assertThat(deleted.get(0)[2].toString()).contains("documentId=" + documentId).contains("versionNo=1");
	}

	@Test
	@DisplayName("질문은 원문 없이 기록되고 1분에 20번을 넘으면 429")
	void chatIsAuditedAndLimited() throws Exception {
		long sessionId = createSession(outsiderToken);

		for (int i = 0; i < 20; i++) {
			ask(outsiderToken, sessionId, "비밀 질문 " + i).andExpect(status().isOk());
		}
		MvcResult limited = ask(outsiderToken, sessionId, "비밀 질문 20").andReturn();

		assertThat(limited.getResponse().getStatus()).isEqualTo(429);
		assertThat(JSON.readTree(limited.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.get("error").get("code").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");

		List<Object[]> questions = auditsFor("CHAT_QUESTION", "CHAT_SESSION", sessionId);
		assertThat(questions).hasSize(20);
		assertThat(questions.get(0)[2].toString()).contains("scopeSize=0").doesNotContain("비밀");

		// 다른 사용자는 영향이 없다
		long leadSession = createSession(leadToken);
		ask(leadToken, leadSession, "질문").andExpect(status().isOk());
	}

	@Test
	@DisplayName("이메일 사용자 조회는 1분에 30번을 넘으면 429")
	void lookupIsLimited() throws Exception {
		for (int i = 0; i < 30; i++) {
			int code = mvc.perform(get("/users/lookup").param("email", "nobody@example.com")
							.header(HttpHeaders.AUTHORIZATION, leadToken))
					.andReturn().getResponse().getStatus();
			assertThat(code).isNotEqualTo(429);
		}
		mvc.perform(get("/users/lookup").param("email", "nobody@example.com")
						.header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
	}

	private long createSession(String token) throws Exception {
		MvcResult result = mvc.perform(post("/chat/sessions").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isCreated()).andReturn();
		return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
				.get("data").get("sessionId").asLong();
	}

	private org.springframework.test.web.servlet.ResultActions ask(String token, long sessionId, String question)
			throws Exception {
		return mvc.perform(post("/ai/chat").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(java.util.Map.of("sessionId", sessionId, "question", question))));
	}

	private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("condition not met within " + WAIT);
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("doq-audit-it");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
