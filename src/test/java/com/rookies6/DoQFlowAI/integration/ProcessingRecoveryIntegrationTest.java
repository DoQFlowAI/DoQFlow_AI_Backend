package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.security.JwtProvider;
import com.rookies6.DoQFlowAI.service.AiClientService;
import com.rookies6.DoQFlowAI.service.ProcessingRecovery;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동 시 끊긴 업로드 후처리 복구 통합 테스트
 *
 * 재시작으로 PROCESSING에 남은 문서·버전을 실제 DB에 만들고, 기동 시 실행되는 복구를 다시 불러
 * FAILED로 바뀌는지, 다른 상태는 그대로인지, 그 뒤 삭제할 수 있는지 확인한다.
 *
 * DB가 있을 때만 돈다 (다른 통합 테스트와 같은 환경변수).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class ProcessingRecoveryIntegrationTest {

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
	@Autowired DocumentRepository documents;
	@Autowired DocumentVersionRepository versions;
	@Autowired ProcessingRecovery recovery;

	private record Fixture(AppUser lead, Long stuckDocId, Long stuckVersionId, Long readyVersionId,
			Long readyDocId, Long emptyDocId) {
	}

	private Fixture stuckAfterRestart() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return tx.execute(status -> {
			AppUser lead = AppUser.builder().email("lead-" + suffix + "@example.com").passwordHash("x")
					.name("팀장").rankLevel(2).build();
			em.persist(lead);

			// v1은 끝났고, v2를 올린 뒤 추출 중에 재시작된 문서
			Document stuck = Document.builder().name("처리 중 재시작").uploadedBy(lead).build();
			em.persist(stuck);
			DocumentVersion v1 = version(stuck, 1, suffix);
			v1.completeExtraction("본문");
			em.persist(v1);
			DocumentVersion v2 = version(stuck, 2, suffix);
			em.persist(v2);
			stuck.changeCurrentVersion(2);
			stuck.changeStatus(DocumentStatus.PROCESSING);

			Document ready = Document.builder().name("정상 문서").uploadedBy(lead).build();
			em.persist(ready);
			ready.changeStatus(DocumentStatus.READY);
			Document empty = Document.builder().name("빈 문서").uploadedBy(lead).build();
			em.persist(empty);

			return new Fixture(lead, stuck.getId(), v2.getId(), v1.getId(), ready.getId(), empty.getId());
		});
	}

	private static DocumentVersion version(Document document, int versionNo, String suffix) {
		return DocumentVersion.builder().document(document).versionNo(versionNo)
				.originalFilename("v" + versionNo + ".pdf").storedFilename(suffix + "-" + versionNo + ".pdf")
				.mimeType("application/pdf").fileSize(1).contentHash(suffix + versionNo).build();
	}

	@Test
	@DisplayName("재시작으로 PROCESSING에 남은 문서·버전은 FAILED가 되고 다른 상태는 그대로다")
	void recoversStuckProcessing() {
		Fixture f = stuckAfterRestart();
		assertThat(documents.findById(f.stuckDocId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.PROCESSING);

		recovery.recover(); // 기동 시(ApplicationReadyEvent) 실행되는 것과 같은 메서드

		assertThat(documents.findById(f.stuckDocId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
		assertThat(versions.findById(f.stuckVersionId()).orElseThrow().getStatus()).isEqualTo(VersionStatus.FAILED);
		assertThat(versions.findById(f.readyVersionId()).orElseThrow().getStatus()).isEqualTo(VersionStatus.READY);
		assertThat(documents.findById(f.readyDocId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.READY);
		assertThat(documents.findById(f.emptyDocId()).orElseThrow().getStatus()).isEqualTo(DocumentStatus.EMPTY);
	}

	@Test
	@DisplayName("복구 전에는 삭제가 막히고(400) 복구 뒤에는 화면에서 지울 수 있다")
	void stuckDocumentCanBeDeletedAfterRecovery() throws Exception {
		Fixture f = stuckAfterRestart();
		String token = "Bearer " + jwt.createAccessToken(f.lead().getPublicId());

		mvc.perform(delete("/documents/{id}", f.stuckDocId()).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isBadRequest());

		recovery.recover();

		mvc.perform(delete("/documents/{id}", f.stuckDocId()).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isNoContent());
		assertThat(documents.findById(f.stuckDocId())).isEmpty();
	}
}
