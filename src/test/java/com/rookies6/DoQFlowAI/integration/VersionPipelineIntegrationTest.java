package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.dto.response.CompareResponse;
import com.rookies6.DoQFlowAI.dto.response.HistoryResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.SummaryStatus;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.exception.DeleteNotAllowedException;
import com.rookies6.DoQFlowAI.exception.UploadValidationException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.service.AiClientService;
import com.rookies6.DoQFlowAI.service.DiffService;
import com.rookies6.DoQFlowAI.service.DocumentService;
import com.rookies6.DoQFlowAI.service.FileKind;
import com.rookies6.DoQFlowAI.service.VersionHistoryService;
import com.rookies6.DoQFlowAI.service.VersionService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 버전 업로드 → 후처리 → 변천사·비교·원문 보기 → 버전·문서 삭제 통합 테스트 < API 11, 18, 21, 22, 23, 24 >
 *
 * 실제 MariaDB(Flyway V1 스키마)와 실제 트랜잭션·비동기 스레드풀로 돌린다. AI Module만 가짜(MockitoBean)다.
 * 단위 테스트로는 볼 수 없는 것을 확인한다 — 커밋 뒤 후처리 시작, 후처리 스레드의 저장, JSON 컬럼, CASCADE 삭제.
 *
 * DB가 있을 때만 돈다. 환경변수 DOQ_IT_DB_URL이 없으면 건너뛴다 (팀원 PC·CI에서 실패하지 않게).
 *   DOQ_IT_DB_URL=jdbc:mariadb://127.0.0.1:33077/doqit DOQ_IT_DB_USER=... DOQ_IT_DB_PASSWORD=... \
 *     ./mvnw test -Dtest=VersionPipelineIntegrationTest
 * 운영·개발 DB를 가리키면 안 된다. 테스트용 빈 DB를 쓴다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class VersionPipelineIntegrationTest {

	private static final Path UPLOAD_DIR = createTempDir();
	private static final Duration WAIT = Duration.ofSeconds(15);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> System.getenv("DOQ_IT_DB_URL"));
		registry.add("spring.datasource.username", () -> System.getenv("DOQ_IT_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("DOQ_IT_DB_PASSWORD"));
		registry.add("app.upload-dir", UPLOAD_DIR::toString);
		// 테스트 전용 값. 실제 비밀값을 쓰지 않는다 (.env가 없는 CI에서도 뜨게)
		registry.add("jwt.secret", () -> "integration-test-secret-integration-test-secret");
		registry.add("ai.internal-key", () -> "integration-test-key");
	}

	@MockitoBean
	AiClientService ai;

	@Autowired VersionService versionService;
	@Autowired DocumentService documentService;
	@Autowired VersionHistoryService historyService;
	@Autowired DocumentRepository documents;
	@Autowired DocumentVersionRepository versions;
	@Autowired DiffService diffService;
	@Autowired TransactionTemplate tx;
	@Autowired EntityManager em;

	private Long documentId;

	@BeforeEach
	void setUp() {
		// 가짜 AI Module: 추출은 고정 문장, 요약은 요청한 구간 그대로 OK, 색인은 성공
		when(ai.extract(anyLong(), anyString(), anyString())).thenReturn(new AiExtract.Response(
				"1. 목적\n결재는 실장이 승인한다.\n승인 기한은 3일이다.\n끝", 1));
		when(ai.history(anyLong(), anyList())).thenAnswer(invocation -> {
			List<AiHistory.RequestSegment> segments = invocation.getArgument(1);
			return new AiHistory.Response(segments.stream()
					.map(s -> new AiHistory.Segment(s.from(), s.to(), "PROCEDURE", s.from() + "→" + s.to() + " 요약", "OK"))
					.toList());
		});
		when(ai.index(anyLong(), anyLong(), anyInt())).thenReturn(new AiIndex.Response("READY", 3));

		// 등록자(팀장)와 빈 문서, 초대 1건 (문서 삭제 시 CASCADE 확인용)
		documentId = tx.execute(status -> {
			String suffix = UUID.randomUUID().toString().substring(0, 8);
			AppUser lead = AppUser.builder().email("lead-" + suffix + "@example.com").passwordHash("x")
					.name("팀장").rankLevel(2).build();
			AppUser staff = AppUser.builder().email("staff-" + suffix + "@example.com").passwordHash("x")
					.name("사원").rankLevel(1).build();
			em.persist(lead);
			em.persist(staff);
			Document document = Document.builder().name("운영지침").uploadedBy(lead).build();
			em.persist(document);
			em.persist(DocumentPermission.builder().document(document).user(staff).grantedBy(lead).build());
			return document.getId();
		});
	}

	@Test
	@DisplayName("업로드부터 삭제까지 한 흐름")
	void fullFlowFromUploadToDelete() throws Exception {
		// ---------- 업로드 (텍스트 2개 + PDF 1개) ----------
		versionService.uploadBatch(documentId, List.of(
				text("운영지침_v1.md", "1. 목적\n결재는 팀장이 승인한다.\n끝"),
				text("운영지침_v2.txt", "1. 목적\n결재는 실장이 승인한다.\n끝"),
				pdf("운영지침_v3.pdf")));

		awaitDocument(DocumentStatus.READY);
		Document document = documents.findById(documentId).orElseThrow();
		assertThat(document.getCurrentVersionNo()).isEqualTo(3);

		List<DocumentVersion> all = versions.findByDocumentIdOrderByVersionNo(documentId);
		assertThat(all).extracting(DocumentVersion::getStatus).containsOnly(VersionStatus.READY);
		assertThat(all.get(0).getChangeCount()).isNull(); // v1은 변경점 없음
		assertThat(all.get(1).getChangeCount()).isEqualTo(1);
		assertThat(all.get(1).getSummaryStatus()).isEqualTo(SummaryStatus.OK);
		assertThat(all.get(2).getContentText()).contains("승인 기한은 3일이다."); // 추출 결과 저장
		// JSON 컬럼에 저장한 diff를 다시 읽을 수 있다
		assertThat(diffService.fromJson(all.get(2).getDiffJson())).hasSize(all.get(2).getChangeCount());

		DocumentVersion v3 = all.get(2);
		verify(ai).extract(eq(v3.getId()), eq(documentId + "/3/" + v3.getStoredFilename()), eq("application/pdf"));
		verify(ai).index(documentId, v3.getId(), 3);
		verify(ai).deleteIndex(documentId, v3.getId());
		assertThat(storedFiles()).hasSize(3);

		// ---------- 변천사·비교·원문 보기 ----------
		HistoryResponse history = historyService.history(document);
		assertThat(history.segments()).extracting(HistoryResponse.Segment::to).containsExactly(2, 3);

		CompareResponse compare = historyService.compare(document, 1, 3); // 비인접 구간: 요청 시 계산
		assertThat(compare.diffJson()).isNotEmpty();
		assertThat(compare.changeCount()).isEqualTo(compare.diffJson().size());

		assertThat(versionService.getContent(document, 1).kind()).isEqualTo(FileKind.TEXT);
		assertThat(versionService.getContent(document, 3).kind()).isEqualTo(FileKind.DOC);

		// ---------- 같은 파일을 다시 올리면 400, 아무것도 남지 않음 ----------
		assertThatThrownBy(() -> versionService.uploadBatch(documentId,
				List.of(pdf("다시_v3.pdf"), text("v5.html", "<p>x</p>"))))
				.isInstanceOf(UploadValidationException.class);
		assertThat(versions.findMaxVersionNo(documentId)).isEqualTo(3);
		assertThat(storedFiles()).hasSize(3);

		// ---------- 중간 버전 삭제: 1 → 3 구간을 다시 만든다 ----------
		versionService.deleteVersion(documentId, 2);
		awaitTrue(() -> versions.findByDocumentIdAndVersionNo(documentId, 3).orElseThrow().getSummaryStatus() != null);

		history = historyService.history(documents.findById(documentId).orElseThrow());
		assertThat(history.versions()).extracting(HistoryResponse.VersionItem::versionNo).containsExactly(1, 3);
		assertThat(history.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.from()).isEqualTo(1);
			assertThat(segment.summary()).isEqualTo("1→3 요약");
		});
		assertThat(storedFiles()).hasSize(2);

		// ---------- 최신 버전 삭제: v1이 최신이 되고 다시 색인 ----------
		versionService.deleteVersion(documentId, 3);
		awaitDocument(DocumentStatus.READY);
		assertThat(documents.findById(documentId).orElseThrow().getCurrentVersionNo()).isEqualTo(1);
		Long v1Id = versions.findByDocumentIdAndVersionNo(documentId, 1).orElseThrow().getId();
		verify(ai).index(documentId, v1Id, 1);

		// ---------- 마지막 하나는 지울 수 없다 ----------
		assertThatThrownBy(() -> versionService.deleteVersion(documentId, 1))
				.isInstanceOf(DeleteNotAllowedException.class);

		// ---------- 문서 삭제: 버전·초대 행 CASCADE, 벡터·저장 폴더 정리 ----------
		documentService.delete(documentId);

		assertThat(documents.findById(documentId)).isEmpty();
		assertThat(versions.findByDocumentIdOrderByVersionNo(documentId)).isEmpty();
		assertThat(countPermissions()).isZero();
		verify(ai).deleteIndex(documentId, null);
		assertThat(UPLOAD_DIR.resolve(String.valueOf(documentId))).doesNotExist();
	}

	// 컨트롤러가 넘기는 것처럼, 트랜잭션 밖에서 읽은(분리된) 문서
	private Document detachedDocument() {
		return documents.findById(documentId).orElseThrow();
	}

	private long countPermissions() {
		return em.createQuery("select count(p) from DocumentPermission p where p.document.id = :id", Long.class)
				.setParameter("id", documentId)
				.getSingleResult();
	}

	private List<Path> storedFiles() throws IOException {
		Path dir = UPLOAD_DIR.resolve(String.valueOf(documentId));
		if (!Files.exists(dir)) {
			return List.of();
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			return paths.filter(Files::isRegularFile).toList();
		}
	}

	private void awaitDocument(DocumentStatus expected) {
		awaitTrue(() -> documents.findById(documentId).orElseThrow().getStatus() == expected);
	}

	private static void awaitTrue(BooleanSupplier condition) {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition not met within " + WAIT);
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	private static MultipartFile text(String name, String body) {
		return new MockMultipartFile("files", name, "text/plain", body.getBytes(StandardCharsets.UTF_8));
	}

	private static MultipartFile pdf(String name) {
		return new MockMultipartFile("files", name, "application/pdf",
				"%PDF-1.7\nfake pdf body".getBytes(StandardCharsets.UTF_8));
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("doq-it-uploads");
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
