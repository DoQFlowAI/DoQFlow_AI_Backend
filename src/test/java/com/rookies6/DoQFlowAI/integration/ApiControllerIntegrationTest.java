package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rookies6.DoQFlowAI.dto.ai.AiChat;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.security.JwtProvider;
import com.rookies6.DoQFlowAI.service.AiClientService;
import com.rookies6.DoQFlowAI.service.ChatService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문서·버전·변천사·비교·원문 보기·질의응답 API 통합 테스트 < API 8~11, 18~25, 27 >
 *
 * 실제 MariaDB, 실제 보안 필터(JWT), 실제 컨트롤러·서비스·AccessPolicy로 HTTP 요청을 보낸다. AI Module만 가짜다.
 * 확인하는 것 — 응답 봉투와 상태 코드(200·201·204), 토큰 없음 401, 권한 밖 404, 등록자 아님 403,
 * 검색할 문서가 없으면 AI Module(LLM)을 호출하지 않는지.
 *
 * DB가 있을 때만 돈다 (VersionPipelineIntegrationTest와 같은 환경변수). 테스트용 빈 DB를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class ApiControllerIntegrationTest {

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
	@Autowired DocumentVersionRepository versions;

	private String leadToken;
	private String invitedToken;
	private String outsiderToken;
	private Long documentId;

	@BeforeEach
	void setUp() {
		reset(ai);
		when(ai.history(anyLong(), anyList())).thenAnswer(invocation -> {
			List<AiHistory.RequestSegment> segments = invocation.getArgument(1);
			return new AiHistory.Response(segments.stream()
					.map(s -> new AiHistory.Segment(s.from(), s.to(), "PROCEDURE", "요약", "OK"))
					.toList());
		});
		when(ai.index(anyLong(), anyLong(), anyInt())).thenReturn(new AiIndex.Response("READY", 1));

		// 팀장(등록자), 초대를 수락한 사원, 초대받지 않은 사원과 빈 문서
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		AppUser[] users = tx.execute(status -> {
			AppUser lead = user("lead-" + suffix, 2);
			AppUser invited = user("invited-" + suffix, 1);
			AppUser outsider = user("outsider-" + suffix, 1);
			Document document = Document.builder().name("운영지침").description("설명").uploadedBy(lead).build();
			em.persist(document);
			DocumentPermission permission = DocumentPermission.builder()
					.document(document).user(invited).grantedBy(lead).build();
			ReflectionTestUtils.setField(permission, "status", PermissionStatus.ACCEPTED);
			em.persist(permission);
			documentId = document.getId();
			return new AppUser[] {lead, invited, outsider};
		});
		leadToken = bearer(users[0]);
		invitedToken = bearer(users[1]);
		outsiderToken = bearer(users[2]);
	}

	private AppUser user(String prefix, int rankLevel) {
		AppUser user = AppUser.builder().email(prefix + "@example.com").passwordHash("x")
				.name(rankLevel == 2 ? "팀장" : "사원").rankLevel(rankLevel).build();
		em.persist(user);
		return user;
	}

	private String bearer(AppUser user) {
		return "Bearer " + jwt.createAccessToken(user.getPublicId());
	}

	private static MockMultipartFile text(String name, String content) {
		return new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
	}

	private void uploadTwoVersionsAndWait() throws Exception {
		mvc.perform(multipart("/documents/{id}/versions", documentId)
						.file(text("v1.txt", "1. 목적\n결재는 팀장이 승인한다.\n끝"))
						.file(text("v2.txt", "1. 목적\n결재는 실장이 승인한다.\n끝"))
						.header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.versions.length()").value(2));
		waitUntil(() -> documents.findById(documentId).orElseThrow().getStatus() == DocumentStatus.READY);
	}

	@Test
	@DisplayName("토큰이 없으면 401")
	void returns401WithoutToken() throws Exception {
		mvc.perform(get("/documents")).andExpect(status().isUnauthorized());
		mvc.perform(post("/ai/chat").contentType(MediaType.APPLICATION_JSON)
				.content("{\"sessionId\": 1, \"question\": \"질문\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("등록자는 업로드 조회 수정 삭제를 모두 할 수 있다")
	void uploaderCanUploadReadUpdateAndDelete() throws Exception {
		uploadTwoVersionsAndWait();

		mvc.perform(get("/documents").header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].documentId").value(documentId))
				.andExpect(jsonPath("$.data.content[0].owned").value(true));
		mvc.perform(get("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("운영지침"));
		mvc.perform(get("/documents/{id}/versions", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2));
		mvc.perform(get("/documents/{id}/versions/2", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.versionNo").value(2));
		mvc.perform(get("/documents/{id}/versions/1/content", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content").value("1. 목적\n결재는 팀장이 승인한다.\n끝"));
		mvc.perform(get("/documents/{id}/history", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.segments[0].summaryStatus").value("OK"));
		mvc.perform(get("/documents/{id}/compare", documentId).param("from", "1").param("to", "2")
						.header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.changeCount").value(1));
		mvc.perform(get("/documents/{id}/compare", documentId).param("from", "1").param("to", "1")
						.header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isBadRequest());

		mvc.perform(patch("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, leadToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"운영지침 개정\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("운영지침 개정"));

		mvc.perform(delete("/documents/{id}/versions/1", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isNoContent());
		mvc.perform(get("/documents/{id}/versions/1", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));

		mvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isNoContent());
		mvc.perform(get("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, leadToken))
				.andExpect(status().isNotFound());
		verify(ai).deleteIndex(documentId, null);
	}

	@Test
	@DisplayName("초대를 수락한 사원은 읽기만 되고 쓰기는 403")
	void invitedViewerCanReadButWriteIs403() throws Exception {
		uploadTwoVersionsAndWait();

		mvc.perform(get("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, invitedToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.owned").value(false));
		mvc.perform(get("/documents/{id}/history", documentId).header(HttpHeaders.AUTHORIZATION, invitedToken))
				.andExpect(status().isOk());

		mvc.perform(patch("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, invitedToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"변경\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mvc.perform(multipart("/documents/{id}/versions", documentId).file(text("v3.txt", "새 본문"))
						.header(HttpHeaders.AUTHORIZATION, invitedToken))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/documents/{id}/versions/1", documentId).header(HttpHeaders.AUTHORIZATION, invitedToken))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, invitedToken))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("초대받지 않은 사원에게는 문서의 존재가 보이지 않는다")
	void outsiderCannotSeeThatDocumentExists() throws Exception {
		uploadTwoVersionsAndWait();

		for (String path : List.of("/documents/{id}", "/documents/{id}/versions", "/documents/{id}/versions/1",
				"/documents/{id}/versions/1/content", "/documents/{id}/history")) {
			mvc.perform(get(path, documentId).header(HttpHeaders.AUTHORIZATION, outsiderToken))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
		}
		mvc.perform(get("/documents/{id}/compare", documentId).param("from", "1").param("to", "2")
						.header(HttpHeaders.AUTHORIZATION, outsiderToken))
				.andExpect(status().isNotFound());
		// 쓰기도 403이 아니라 404 (403이면 문서가 있다는 사실이 드러난다)
		mvc.perform(delete("/documents/{id}", documentId).header(HttpHeaders.AUTHORIZATION, outsiderToken))
				.andExpect(status().isNotFound());
		// 없는 문서와 응답이 같다
		mvc.perform(get("/documents/{id}", 999_999_999L).header(HttpHeaders.AUTHORIZATION, outsiderToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
		mvc.perform(get("/documents").header(HttpHeaders.AUTHORIZATION, outsiderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(0));
	}

	@Test
	@DisplayName("검색할 문서가 없으면 AI Module을 호출하지 않고 근거 없음으로 답한다")
	void emptyScopeAnswersNoAnswerWithoutCallingAi() throws Exception {
		uploadTwoVersionsAndWait();
		long sessionId = createSession(outsiderToken);

		String body = ask(outsiderToken, sessionId, "결재는 누가 승인해?");

		assertThat(body).contains("\"noAnswer\":true").contains(ChatService.NO_ANSWER_MESSAGE).contains("[DONE]");
		verify(ai, never()).chat(anyString(), anyList(), anyInt());
	}

	@Test
	@DisplayName("읽을 수 있는 문서가 있으면 근거와 토큰을 SSE로 보낸다")
	void streamsSourcesAndTokensWhenReadable() throws Exception {
		uploadTwoVersionsAndWait();
		Long latestVersionId = versions.findTopByDocumentIdOrderByVersionNoDesc(documentId).orElseThrow().getId();
		when(ai.chat(anyString(), anyList(), anyInt())).thenReturn(new AiChat.Result(
				List.of("결재는", " 실장이 승인합니다."), List.of(latestVersionId),
				List.of(new AiChat.Chunk(latestVersionId, documentId, 2, null, 0, "결재는 실장이 승인한다.")), false));
		long sessionId = createSession(invitedToken);

		String body = ask(invitedToken, sessionId, "결재는 누가 승인해?");

		verify(ai).chat("결재는 누가 승인해?", List.of(latestVersionId), 5);
		assertThat(body.indexOf("\"sources\"")).isLessThan(body.indexOf("\"token\""));
		assertThat(body).contains("운영지침").contains("결재는").contains("[DONE]");
	}

	@Test
	@DisplayName("남의 대화로 질문하면 404이고 질문이 비면 400")
	void othersSessionIs404AndBlankQuestionIs400() throws Exception {
		long leadSession = createSession(leadToken);

		mvc.perform(post("/ai/chat").header(HttpHeaders.AUTHORIZATION, invitedToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sessionId\": " + leadSession + ", \"question\": \"질문\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(post("/ai/chat").header(HttpHeaders.AUTHORIZATION, leadToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sessionId\": " + leadSession + ", \"question\": \"  \"}"))
				.andExpect(status().isBadRequest());
		verify(ai, never()).chat(anyString(), anyList(), anyInt());
	}

	private long createSession(String token) throws Exception {
		MvcResult result = mvc.perform(post("/chat/sessions").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		return body.get("data").get("sessionId").asLong();
	}

	private String ask(String token, long sessionId, String question) throws Exception {
		MvcResult result = mvc.perform(post("/ai/chat").header(HttpHeaders.AUTHORIZATION, token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(java.util.Map.of("sessionId", sessionId, "question", question))))
				.andExpect(status().isOk())
				.andReturn();
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
			return Files.createTempDirectory("doq-api-it");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
