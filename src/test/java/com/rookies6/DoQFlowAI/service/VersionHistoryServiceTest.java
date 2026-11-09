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
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
		service = new VersionHistoryService(versions, diffService);

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
	void 변천사는_두번째_버전부터_구간을_만들고_처리중인_버전은_뺀다() {
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
	void 버전이_삭제되어_번호가_비어도_실제_직전_버전과_구간을_만든다() {
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
	void 인접_구간은_저장된_diff를_그대로_쓴다() {
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
	void 비인접_구간은_요청_시점에_계산한다() {
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
	void from이_to보다_커도_요청한_방향으로_계산한다() {
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
	void 본문이_아직_없는_버전은_빈_본문으로_비교한다() {
		DocumentVersion v1 = version(1, "a");
		DocumentVersion v2 = version(2, null);
		stubVersion(v1);
		stubVersion(v2);

		CompareResponse response = service.compare(document, 1, 2);

		assertThat(response.toText()).isEmpty();
		assertThat(response.diffJson()).containsExactly(new DiffBlock("DELETE", 0, 1, 0, 0));
	}

	@Test
	void 없는_버전이면_VersionNotFoundException() {
		stubVersion(version(1, "a"));
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.compare(document, 1, 9))
				.isInstanceOf(VersionNotFoundException.class);
	}
}
