package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 질의응답 scope 쿼리 통합 테스트 (Backend 개발 명세서 4.4 findLatestIdsByDocumentIds)
 *
 * LLM에 보내기 전 검색 범위가 "존재하고, 읽을 수 있고, READY인 문서의 최신 버전"만인지 실제 MariaDB로 확인한다.
 * 범위가 비면 질의응답은 AI Module을 호출하지 않는다.
 * 환경변수 DOQ_IT_DB_URL이 있을 때만 돈다 (VersionPipelineIntegrationTest 참고). 테스트가 끝나면 롤백된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class SearchScopeQueryIntegrationTest {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> System.getenv("DOQ_IT_DB_URL"));
		registry.add("spring.datasource.username", () -> System.getenv("DOQ_IT_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("DOQ_IT_DB_PASSWORD"));
	}

	@Autowired EntityManager em;
	@Autowired DocumentVersionRepository versions;

	private AppUser me;
	private AppUser other;

	@BeforeEach
	void setUp() {
		me = user("me");
		other = user("other");
	}

	private AppUser user(String prefix) {
		AppUser user = AppUser.builder().email(prefix + "-" + UUID.randomUUID() + "@example.com")
				.passwordHash("x").name(prefix).rankLevel(2).build();
		em.persist(user);
		return user;
	}

	private Document document(AppUser owner, DocumentStatus status) {
		Document document = Document.builder().name("문서").uploadedBy(owner).build();
		document.changeStatus(status);
		em.persist(document);
		return document;
	}

	private DocumentVersion version(Document document, int versionNo, boolean ready) {
		DocumentVersion version = DocumentVersion.builder().document(document).versionNo(versionNo)
				.originalFilename("v.txt").storedFilename(UUID.randomUUID() + ".txt").mimeType("text/plain")
				.fileSize(1).contentHash("h" + versionNo).build();
		if (ready) {
			version.completeExtraction("본문");
		} else {
			version.failExtraction();
		}
		em.persist(version);
		return version;
	}

	private void invite(Document document, PermissionStatus status) {
		DocumentPermission permission = DocumentPermission.builder().document(document).user(me).grantedBy(other).build();
		ReflectionTestUtils.setField(permission, "status", status);
		em.persist(permission);
	}

	private List<Long> scope(Document... accepted) {
		em.flush();
		em.clear();
		return versions.findLatestIdsByDocumentIds(
				java.util.Arrays.stream(accepted).map(Document::getId).toList(), me.getId());
	}

	@Test
	@DisplayName("읽을 수 있고 READY인 문서의 최신 버전만 들어간다")
	void includesOnlyLatestVersionOfReadableReadyDocuments() {
		Document mine = document(me, DocumentStatus.READY);
		version(mine, 1, true);
		DocumentVersion mineLatest = version(mine, 2, true);

		Document invited = document(other, DocumentStatus.READY);
		DocumentVersion invitedLatest = version(invited, 1, true);
		invite(invited, PermissionStatus.ACCEPTED);

		assertThat(scope(invited)).containsExactlyInAnyOrder(mineLatest.getId(), invitedLatest.getId());
	}

	@Test
	@DisplayName("수락하지 않았거나 초대받지 않은 문서는 빠진다")
	void excludesPendingAndUninvitedDocuments() {
		Document pending = document(other, DocumentStatus.READY);
		version(pending, 1, true);
		invite(pending, PermissionStatus.PENDING);
		Document notInvited = document(other, DocumentStatus.READY);
		version(notInvited, 1, true);

		// 수락 목록(documentIds)에는 ACCEPTED만 들어온다 (AccessPolicy가 findAcceptedDocumentIds로 만든다)
		assertThat(scope()).isEmpty();
	}

	@Test
	@DisplayName("후처리 중이거나 실패했거나 버전이 없는 문서는 빠진다")
	void excludesProcessingFailedAndEmptyDocuments() {
		version(document(me, DocumentStatus.PROCESSING), 1, true);
		version(document(me, DocumentStatus.FAILED), 1, true);
		document(me, DocumentStatus.EMPTY);

		assertThat(scope()).isEmpty();
	}

	@Test
	@DisplayName("마지막 버전의 추출이 실패했으면 빠진다")
	void excludesDocumentWhoseLatestVersionFailed() {
		Document document = document(me, DocumentStatus.READY);
		version(document, 1, true);
		version(document, 2, false); // 마지막 버전 FAILED. 이전 버전으로 대신 검색하지 않는다

		assertThat(scope()).isEmpty();
	}

	@Test
	@DisplayName("검색할 문서가 하나도 없으면 빈 목록이라 LLM을 부르지 않는다")
	void returnsEmptyScopeWhenNothingSearchable() {
		assertThat(scope()).isEmpty();
	}
}
