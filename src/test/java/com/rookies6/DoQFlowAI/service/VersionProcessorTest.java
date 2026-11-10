package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.SummaryStatus;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** 버전 업로드 후처리 단위 테스트 < API 18 > — 가짜 Repository와 가짜 AI Module 클라이언트를 쓴다 */
class VersionProcessorTest {

	private static final long DOCUMENT_ID = 7L;
	private static final String PDF = "application/pdf";

	@TempDir
	Path uploadDir;

	private DocumentRepository documents;
	private DocumentVersionRepository versions;
	private AiClientService ai;
	private VersionProcessor processor;
	private Document document;

	@BeforeEach
	void setUp() {
		documents = mock(DocumentRepository.class);
		versions = mock(DocumentVersionRepository.class);
		ai = mock(AiClientService.class);
		processor = new VersionProcessor(documents, versions, ai,
				new DiffService(JsonMapper.builder().build()), new VersionFileStorage(uploadDir.toString()));

		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		document = Document.builder().name("운영지침").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
		document.changeStatus(DocumentStatus.PROCESSING);
		when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

		when(ai.index(anyLong(), anyLong(), anyInt())).thenReturn(new AiIndex.Response("READY", 3));
	}

	private DocumentVersion version(long id, int versionNo, String mimeType) {
		DocumentVersion version = DocumentVersion.builder().document(document).versionNo(versionNo)
				.originalFilename("v" + versionNo).storedFilename("uuid-" + versionNo).mimeType(mimeType)
				.fileSize(10).contentHash("hash-" + versionNo).build();
		ReflectionTestUtils.setField(version, "id", id);
		return version;
	}

	private void uploaded(DocumentVersion... created) {
		when(versions.findAllById(anyList())).thenReturn(new ArrayList<>(List.of(created)));
	}

	private void extractReturns(long versionId, String text) {
		when(ai.extract(eq(versionId), anyString(), anyString())).thenReturn(new AiExtract.Response(text, 1));
	}

	private void historyReturns(int from, int to, String status) {
		when(ai.history(eq(DOCUMENT_ID), anyList())).thenAnswer(invocation -> {
			List<AiHistory.RequestSegment> segments = invocation.getArgument(1);
			AiHistory.RequestSegment s = segments.get(0);
			return new AiHistory.Response(List.of("OK".equals(status)
					? new AiHistory.Segment(s.from(), s.to(), "PROCEDURE", "승인자 변경", "OK")
					: new AiHistory.Segment(s.from(), s.to(), null, null, "FAILED")));
		});
	}

	@Test
	@DisplayName("문서 계열은 추출하고 구간 요약 후 마지막 버전만 색인해 READY")
	void documentKindExtractsSummarizesIndexesLatestAndReady() {
		DocumentVersion v1 = version(11, 1, PDF);
		DocumentVersion v2 = version(12, 2, PDF);
		uploaded(v1, v2);
		extractReturns(11, "결재는 팀장이 승인한다.");
		extractReturns(12, "결재는 실장이 승인한다.");
		historyReturns(1, 2, "OK");

		processor.run(DOCUMENT_ID, null, List.of(11L, 12L));

		// 추출 경로는 업로드 폴더 기준 상대 경로
		verify(ai).extract(11L, "7/1/uuid-1", PDF);
		assertThat(v1.getStatus()).isEqualTo(VersionStatus.READY);
		assertThat(v1.getChangeCount()).isNull(); // v1은 변경점 없음
		assertThat(v2.getChangeCount()).isEqualTo(1);
		assertThat(v2.getDiffJson()).contains("\"type\":\"CHANGE\"");
		assertThat(v2.getSummaryStatus()).isEqualTo(SummaryStatus.OK);
		assertThat(v2.getChangeType()).isEqualTo("PROCEDURE");

		verify(ai, times(1)).index(DOCUMENT_ID, 12L, 2);
		verify(ai).deleteIndex(DOCUMENT_ID, 12L);
		assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
	}

	@Test
	@DisplayName("history에는 구간 하나씩 변경 블록 텍스트만 보낸다")
	void sendsOneSegmentOfBlockTextPerHistoryCall() {
		DocumentVersion v1 = version(11, 1, "text/plain");
		v1.completeExtraction("a\nb\nc");
		DocumentVersion v2 = version(12, 2, "text/plain");
		v2.completeExtraction("a\nB\nc");
		DocumentVersion v3 = version(13, 3, "text/plain");
		v3.completeExtraction("a\nB\nc\nd");
		uploaded(v1, v2, v3);
		historyReturns(0, 0, "OK");

		processor.run(DOCUMENT_ID, null, List.of(11L, 12L, 13L));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<AiHistory.RequestSegment>> sent = ArgumentCaptor.forClass(List.class);
		verify(ai, times(2)).history(eq(DOCUMENT_ID), sent.capture());
		assertThat(sent.getAllValues()).allSatisfy(segments -> assertThat(segments).hasSize(1));
		assertThat(sent.getAllValues().get(0).get(0)).isEqualTo(new AiHistory.RequestSegment(1, 2,
				List.of(new AiHistory.Block("CHANGE", List.of("b"), List.of("B")))));
		assertThat(sent.getAllValues().get(1).get(0)).isEqualTo(new AiHistory.RequestSegment(2, 3,
				List.of(new AiHistory.Block("INSERT", List.of(), List.of("d")))));
		// 텍스트 계열은 추출을 다시 부르지 않는다
		verify(ai, never()).extract(anyLong(), anyString(), anyString());
	}

	@Test
	@DisplayName("변경이 없는 구간은 SKIPPED이고 LLM을 부르지 않는다")
	void unchangedSegmentIsSkippedWithoutLlm() {
		DocumentVersion v1 = version(11, 1, "text/plain");
		v1.completeExtraction("same");
		DocumentVersion v2 = version(12, 2, "text/plain");
		v2.completeExtraction("same");
		uploaded(v1, v2);

		processor.run(DOCUMENT_ID, null, List.of(11L, 12L));

		assertThat(v2.getChangeCount()).isZero();
		assertThat(v2.getSummaryStatus()).isEqualTo(SummaryStatus.SKIPPED);
		verify(ai, never()).history(anyLong(), anyList());
	}

	@Test
	@DisplayName("추가 업로드는 직전 버전과 새 첫 버전 사이 구간도 만든다")
	void additionalUploadBuildsSegmentFromPreviousVersion() {
		DocumentVersion v3 = version(13, 3, "text/plain");
		v3.completeExtraction("old");
		DocumentVersion v4 = version(14, 4, "text/plain");
		v4.completeExtraction("new");
		when(versions.findById(13L)).thenReturn(Optional.of(v3));
		uploaded(v4);
		historyReturns(3, 4, "OK");

		processor.run(DOCUMENT_ID, 13L, List.of(14L));

		assertThat(v4.getChangeCount()).isEqualTo(1);
		assertThat(v4.getSummaryStatus()).isEqualTo(SummaryStatus.OK);
		verify(ai).index(DOCUMENT_ID, 14L, 4);
	}

	@Test
	@DisplayName("추출에 실패한 버전만 FAILED이고 그 버전이 낀 구간은 만들지 않는다")
	void failedExtractionSkipsItsSegments() {
		DocumentVersion v1 = version(11, 1, PDF);
		DocumentVersion v2 = version(12, 2, PDF);
		DocumentVersion v3 = version(13, 3, PDF);
		uploaded(v1, v2, v3);
		extractReturns(11, "a");
		when(ai.extract(eq(12L), anyString(), anyString())).thenThrow(new AiModuleException("scan pdf"));
		extractReturns(13, "c");

		processor.run(DOCUMENT_ID, null, List.of(11L, 12L, 13L));

		assertThat(v2.getStatus()).isEqualTo(VersionStatus.FAILED);
		assertThat(v2.getChangeCount()).isNull();
		assertThat(v3.getChangeCount()).isNull(); // 2 → 3 구간도 만들 수 없다
		verify(ai, never()).history(anyLong(), anyList());
		// 마지막 버전은 정상이라 색인하고 READY
		assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
	}

	@Test
	@DisplayName("추출 본문이 비어 있으면 FAILED")
	void blankExtractionIsFailed() {
		DocumentVersion v1 = version(11, 1, PDF);
		uploaded(v1);
		extractReturns(11, "  ");

		processor.run(DOCUMENT_ID, null, List.of(11L));

		assertThat(v1.getStatus()).isEqualTo(VersionStatus.FAILED);
		verify(ai, never()).index(anyLong(), anyLong(), anyInt());
		assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
	}

	@Test
	@DisplayName("요약 호출이 실패해도 그 구간만 FAILED이고 계속 진행한다")
	void summaryFailureMarksOnlyThatSegment() {
		DocumentVersion v1 = version(11, 1, "text/plain");
		v1.completeExtraction("a");
		DocumentVersion v2 = version(12, 2, "text/plain");
		v2.completeExtraction("b");
		uploaded(v1, v2);
		when(ai.history(anyLong(), anyList())).thenThrow(new AiModuleException("timeout"));

		processor.run(DOCUMENT_ID, null, List.of(11L, 12L));

		assertThat(v2.getChangeCount()).isEqualTo(1);
		assertThat(v2.getSummaryStatus()).isEqualTo(SummaryStatus.FAILED);
		assertThat(v2.getChangeType()).isNull();
		assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
	}

	@Test
	@DisplayName("AI가 구간을 FAILED로 돌려주면 FAILED로 저장한다")
	void storesFailedSegmentFromAi() {
		DocumentVersion v1 = version(11, 1, "text/plain");
		v1.completeExtraction("a");
		DocumentVersion v2 = version(12, 2, "text/plain");
		v2.completeExtraction("b");
		uploaded(v1, v2);
		historyReturns(1, 2, "FAILED");

		processor.run(DOCUMENT_ID, null, List.of(11L, 12L));

		assertThat(v2.getSummaryStatus()).isEqualTo(SummaryStatus.FAILED);
	}

	@Test
	@DisplayName("색인 실패 시 문서 계열은 추출을 한 번 다시 하고 색인한다")
	void indexFailureReExtractsOnceForDocumentKind() {
		DocumentVersion v1 = version(11, 1, PDF);
		uploaded(v1);
		extractReturns(11, "본문");
		when(ai.index(DOCUMENT_ID, 11L, 1))
				.thenThrow(new AiModuleException("no extract cache"))
				.thenReturn(new AiIndex.Response("READY", 3));

		processor.run(DOCUMENT_ID, null, List.of(11L));

		verify(ai, times(2)).extract(eq(11L), anyString(), anyString());
		verify(ai, times(2)).index(DOCUMENT_ID, 11L, 1);
		assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
	}

	@Test
	@DisplayName("다시 해도 색인에 실패하면 문서 FAILED이고 이전 벡터는 지우지 않는다")
	void repeatedIndexFailureMarksFailedAndKeepsVectors() {
		DocumentVersion v1 = version(11, 1, PDF);
		uploaded(v1);
		extractReturns(11, "본문");
		when(ai.index(anyLong(), anyLong(), anyInt())).thenThrow(new AiModuleException("down"));

		processor.run(DOCUMENT_ID, null, List.of(11L));

		verify(ai, never()).deleteIndex(any(), any());
		assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
	}

	@Test
	@DisplayName("이전 벡터 삭제가 실패해도 문서는 READY")
	void vectorCleanupFailureStillReady() {
		DocumentVersion v1 = version(11, 1, "text/plain");
		v1.completeExtraction("a");
		uploaded(v1);
		when(ai.deleteIndex(DOCUMENT_ID, 11L)).thenThrow(new AiModuleException("down"));

		processor.run(DOCUMENT_ID, null, List.of(11L));

		assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
	}

	@Test
	@DisplayName("예상하지 못한 오류가 나도 PROCESSING에 멈추지 않고 FAILED")
	void unexpectedErrorEndsFailedNotProcessing() {
		when(versions.findAllById(anyList())).thenThrow(new IllegalStateException("db down"));

		processor.process(DOCUMENT_ID, null, List.of(11L));

		assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
	}
}
