package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.response.CompareResponse;
import com.rookies6.DoQFlowAI.dto.response.HistoryResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.SummaryStatus;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.exception.VersionNotReadyException;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** 변천사·두 버전 비교 단위 테스트 < API 22, API 24 > — DB 대신 가짜 Repository를 쓴다. */
class VersionHistoryServiceTest {

	private static final long DOCUMENT_ID = 7L;

	private DocumentVersionRepository versions;
	private DiffService diffService;
	private VersionHistoryService service;
	private Document document;

	@BeforeEach
	void setUp() {
		versions = mock(DocumentVersionRepository.class);
		diffService = new DiffService(JsonMapper.builder().build());
		service = new VersionHistoryService(versions, diffService, null);

		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		document = Document.builder().name("휴가 규정").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
	}

	private DocumentVersion version(int versionNo, String text) {
		DocumentVersion version = DocumentVersion.builder()
				.document(document)
				.versionNo(versionNo)
				.originalFilename("v" + versionNo + ".pdf")
				.storedFilename("stored-" + versionNo + ".pdf")
				.mimeType("application/pdf")
				.fileSize(100)
				.contentHash("hash-" + versionNo)
				.build();
		if (text != null) {
			version.completeExtraction(text);
		}
		return version;
	}

	private void stubVersion(DocumentVersion version) {
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, version.getVersionNo()))
				.thenReturn(Optional.of(version));
	}

	// ---------- API 22 변천사 ----------

	@Test
	@DisplayName("변천사는 두번째 버전부터 구간을 만들고 처리중인 버전은 뺀다")
	void historyStartsAtSecondVersionAndSkipsProcessing() {
		DocumentVersion v1 = version(1, "a");
		DocumentVersion v2 = version(2, "b");
		v2.updateDiff("[]", 3);
		v2.updateSummary(SummaryStatus.OK, "PROCEDURE", "승인자 변경");
		DocumentVersion v3 = version(3, "c");
		v3.updateDiff("[]", 5);
		v3.updateSummary(SummaryStatus.FAILED, null, null);
		DocumentVersion v4 = version(4, null); // 아직 후처리 중
		when(versions.findByDocumentIdOrderByVersionNo(DOCUMENT_ID)).thenReturn(List.of(v1, v2, v3, v4));

		HistoryResponse response = service.history(document);

		assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
		assertThat(response.versions()).extracting(HistoryResponse.VersionItem::versionNo).containsExactly(1, 2, 3, 4);
		assertThat(response.segments()).containsExactly(
				new HistoryResponse.Segment(1, 2, "PROCEDURE", "승인자 변경", 3, "OK"),
				new HistoryResponse.Segment(2, 3, null, null, 5, "FAILED"));
	}

	@Test
	@DisplayName("버전이 삭제되어 번호가 비어도 실제 직전 버전과 구간을 만든다")
	void historyPairsWithActualPreviousAfterGap() {
		DocumentVersion v1 = version(1, "a");
		DocumentVersion v3 = version(3, "c");
		v3.updateDiff("[]", 0);
		v3.updateSummary(SummaryStatus.SKIPPED, null, null);
		when(versions.findByDocumentIdOrderByVersionNo(DOCUMENT_ID)).thenReturn(List.of(v1, v3));

		HistoryResponse response = service.history(document);

		assertThat(response.segments()).containsExactly(
				new HistoryResponse.Segment(1, 3, null, null, 0, "SKIPPED"));
	}

	// ---------- API 24 두 버전 비교 ----------

	@Test
	@DisplayName("인접 구간은 저장된 diff를 그대로 쓴다")
	void adjacentCompareUsesStoredDiff() {
		DocumentVersion v1 = version(1, "a\nb");
		DocumentVersion v2 = version(2, "a\nc");
		// 저장값을 재사용하는지 확인하려고, 실제 계산 결과와 다른 블록을 저장해 둔다.
		v2.updateDiff("[{\"type\":\"INSERT\",\"fromLine\":9,\"fromCount\":0,\"toLine\":9,\"toCount\":1}]", 1);
		stubVersion(v1);
		stubVersion(v2);
		when(versions.findPreviousVersionNo(DOCUMENT_ID, 2)).thenReturn(1);

		CompareResponse response = service.compare(document, 1, 2);

		assertThat(response.diffJson()).containsExactly(new DiffBlock("INSERT", 9, 0, 9, 1));
		assertThat(response.changeCount()).isEqualTo(1);
		assertThat(response.fromText()).isEqualTo("a\nb");
		assertThat(response.toText()).isEqualTo("a\nc");
	}

	@Test
	@DisplayName("비인접 구간은 요청 시점에 계산한다")
	void nonAdjacentCompareComputesOnRequest() {
		DocumentVersion v1 = version(1, "a\nb");
		DocumentVersion v3 = version(3, "a\nb\nc");
		v3.updateDiff("[]", 0);
		stubVersion(v1);
		stubVersion(v3);
		when(versions.findPreviousVersionNo(DOCUMENT_ID, 3)).thenReturn(2);

		CompareResponse response = service.compare(document, 1, 3);

		assertThat(response.diffJson()).containsExactly(new DiffBlock("INSERT", 2, 0, 2, 1));
		assertThat(response.changeCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("from이 to보다 커도 요청한 방향으로 계산한다")
	void compareKeepsRequestedDirection() {
		DocumentVersion v1 = version(1, "a");
		DocumentVersion v2 = version(2, "a\nb");
		v2.updateDiff("[]", 0);
		stubVersion(v1);
		stubVersion(v2);

		CompareResponse response = service.compare(document, 2, 1);

		assertThat(response.fromVersionNo()).isEqualTo(2);
		assertThat(response.diffJson()).containsExactly(new DiffBlock("DELETE", 1, 1, 1, 0));
		verify(versions, never()).findPreviousVersionNo(DOCUMENT_ID, 1);
	}

	@Test
	@DisplayName("아직 추출 중인 PROCESSING 버전과는 비교하지 않는다")
	void rejectsProcessingVersion() {
		stubVersion(version(1, "a"));
		stubVersion(version(2, null)); // 본문 추출 전

		assertThatThrownBy(() -> service.compare(document, 1, 2))
				.isInstanceOf(VersionNotReadyException.class);
		assertThatThrownBy(() -> service.compare(document, 2, 1))
				.isInstanceOf(VersionNotReadyException.class);
	}

	@Test
	@DisplayName("추출에 실패한 FAILED 버전은 비교 대상에서 제외한다")
	void rejectsFailedVersion() {
		DocumentVersion failed = version(2, null);
		failed.failExtraction();
		stubVersion(version(1, "a"));
		stubVersion(failed);

		assertThatThrownBy(() -> service.compare(document, 1, 2))
				.isInstanceOf(VersionNotReadyException.class);
	}

	@Test
	@DisplayName("없는 버전이면 VersionNotFoundException")
	void missingVersionThrowsVersionNotFound() {
		stubVersion(version(1, "a"));
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.compare(document, 1, 9))
				.isInstanceOf(VersionNotFoundException.class);
	}
}
