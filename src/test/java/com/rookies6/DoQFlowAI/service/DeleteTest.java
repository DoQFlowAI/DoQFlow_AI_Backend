package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.dto.ai.AiIndex;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.SummaryStatus;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import com.rookies6.DoQFlowAI.exception.DeleteNotAllowedException;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** 문서·버전 삭제 단위 테스트 < API 11, API 21 > — 가짜 Repository·AI 클라이언트, 업로드 폴더는 임시 폴더 */
class DeleteTest {

	private static final long DOCUMENT_ID = 7L;

	@TempDir
	Path uploadDir;

	private DocumentRepository documents;
	private DocumentVersionRepository versions;
	private AiClientService ai;
	private VersionProcessor processor;
	private VersionFileStorage storage;
	private Document document;

	@BeforeEach
	void setUp() {
		documents = mock(DocumentRepository.class);
		versions = mock(DocumentVersionRepository.class);
		ai = mock(AiClientService.class);
		processor = mock(VersionProcessor.class);
		storage = new VersionFileStorage(uploadDir.toString());

		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		document = Document.builder().name("운영지침").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
		document.changeStatus(DocumentStatus.READY);
		document.changeCurrentVersion(3);
		when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
	}

	private DocumentVersion version(long id, int versionNo, String text) throws Exception {
		DocumentVersion version = DocumentVersion.builder().document(document).versionNo(versionNo)
				.originalFilename("v" + versionNo + ".txt").storedFilename("uuid-" + versionNo + ".txt")
				.mimeType("text/plain").fileSize(1).contentHash("hash-" + versionNo).build();
		ReflectionTestUtils.setField(version, "id", id);
		version.completeExtraction(text);
		// 실제 저장 파일도 만들어 둔다
		Path file = uploadDir.resolve(String.valueOf(DOCUMENT_ID)).resolve(String.valueOf(versionNo))
				.resolve(version.getStoredFilename());
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return version;
	}

	private Path fileOf(DocumentVersion version) {
		return uploadDir.resolve(String.valueOf(DOCUMENT_ID)).resolve(String.valueOf(version.getVersionNo()))
				.resolve(version.getStoredFilename());
	}

	@Nested
	@DisplayName("버전 삭제")
	class VersionDelete {

		private VersionService service;
		private DocumentVersion v1;
		private DocumentVersion v2;
		private DocumentVersion v3;

		@BeforeEach
		void setUp() throws Exception {
			service = new VersionService(documents, versions, null, storage, processor, null, 1_048_576);
			v1 = version(11, 1, "a");
			v2 = version(12, 2, "b");
			v2.updateDiff("[]", 1);
			v3 = version(13, 3, "c");
			v3.updateDiff("[]", 1);
			v3.updateSummary(SummaryStatus.OK, "WORDING", "표현 변경");
			when(versions.findByDocumentIdOrderByVersionNo(DOCUMENT_ID)).thenReturn(List.of(v1, v2, v3));
		}

		@Test
		@DisplayName("중간 버전을 지우면 뒤 버전 변경점을 비우고 앞뒤 구간을 다시 만든다")
		void deletingMiddleVersionClearsNextAndRebuildsSegment() {
			service.deleteVersion(DOCUMENT_ID, 2);

			verify(versions).delete(v2);
			assertThat(v3.getChangeCount()).isNull();
			assertThat(v3.getSummaryStatus()).isNull();
			assertThat(v3.getChangeType()).isNull();
			assertThat(document.getCurrentVersionNo()).isEqualTo(3); // 최신은 그대로
			assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
			verify(processor).rebuildAfterDelete(DOCUMENT_ID, 11L, 13L, null);
			assertThat(fileOf(v2)).doesNotExist();
			assertThat(fileOf(v1)).exists();
		}

		@Test
		@DisplayName("최신 버전을 지우면 직전 버전이 최신이 되고 재색인한다")
		void deletingLatestVersionPromotesPreviousAndReindexes() {
			service.deleteVersion(DOCUMENT_ID, 3);

			verify(versions).delete(v3);
			assertThat(document.getCurrentVersionNo()).isEqualTo(2);
			assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING); // 재색인이 끝나면 READY
			verify(processor).rebuildAfterDelete(DOCUMENT_ID, null, null, 12L);
			assertThat(fileOf(v3)).doesNotExist();
		}

		@Test
		@DisplayName("맨 앞 버전을 지우면 다음 버전이 v1 역할이라 변경점을 비운다")
		void deletingFirstVersionClearsNextChange() {
			service.deleteVersion(DOCUMENT_ID, 1);

			assertThat(v2.getChangeCount()).isNull();
			assertThat(document.getCurrentVersionNo()).isEqualTo(3);
			verify(processor, never()).rebuildAfterDelete(any(), any(), any(), any());
		}

		@Test
		@DisplayName("버전이 하나뿐이면 400")
		void onlyVersionIs400() {
			when(versions.findByDocumentIdOrderByVersionNo(DOCUMENT_ID)).thenReturn(List.of(v1));

			assertThatThrownBy(() -> service.deleteVersion(DOCUMENT_ID, 1))
					.isInstanceOf(DeleteNotAllowedException.class)
					.extracting(e -> ((DeleteNotAllowedException) e).getErrorCode())
					.isEqualTo(ErrorCode.VALIDATION_ERROR);
			verify(versions, never()).delete(any());
		}

		@Test
		@DisplayName("업로드 후처리 중이면 400")
		void processingIs400() {
			document.changeStatus(DocumentStatus.PROCESSING);

			assertThatThrownBy(() -> service.deleteVersion(DOCUMENT_ID, 2))
					.isInstanceOf(DeleteNotAllowedException.class);
			verify(versions, never()).delete(any());
		}

		@Test
		@DisplayName("없는 버전이면 404")
		void missingVersionIs404() {
			assertThatThrownBy(() -> service.deleteVersion(DOCUMENT_ID, 9))
					.isInstanceOf(VersionNotFoundException.class);
		}
	}

	@Nested
	@DisplayName("버전 삭제 후처리")
	class VersionDeleteProcessing {

		private VersionProcessor realProcessor;

		@BeforeEach
		void setUp() {
			realProcessor = new VersionProcessor(documents, versions, ai,
					new DiffService(JsonMapper.builder().build()), storage);
			when(ai.index(anyLong(), anyLong(), anyInt())).thenReturn(new AiIndex.Response("READY", 3));
		}

		@Test
		@DisplayName("끊어진 구간의 diff와 요약을 다시 만든다")
		void rebuildsDiffAndSummaryOfBrokenSegment() throws Exception {
			DocumentVersion v1 = version(11, 1, "a");
			DocumentVersion v3 = version(13, 3, "c");
			when(versions.findById(11L)).thenReturn(Optional.of(v1));
			when(versions.findById(13L)).thenReturn(Optional.of(v3));
			when(ai.history(anyLong(), anyList())).thenReturn(new AiHistory.Response(
					List.of(new AiHistory.Segment(1, 3, "NUMERIC", "기한 변경", "OK"))));

			realProcessor.rebuildAfterDelete(DOCUMENT_ID, 11L, 13L, null);

			assertThat(v3.getChangeCount()).isEqualTo(1);
			assertThat(v3.getSummaryStatus()).isEqualTo(SummaryStatus.OK);
			assertThat(v3.getChangeType()).isEqualTo("NUMERIC");
			verify(ai, never()).index(anyLong(), anyLong(), anyInt());
		}

		@Test
		@DisplayName("새 최신 버전을 색인하고 나머지 벡터를 지운 뒤 READY")
		void indexesNewLatestThenRemovesOtherVectorsAndReady() throws Exception {
			DocumentVersion v2 = version(12, 2, "b");
			when(versions.findById(12L)).thenReturn(Optional.of(v2));
			document.changeStatus(DocumentStatus.PROCESSING);

			realProcessor.rebuildAfterDelete(DOCUMENT_ID, null, null, 12L);

			verify(ai).index(DOCUMENT_ID, 12L, 2);
			verify(ai).deleteIndex(DOCUMENT_ID, 12L);
			assertThat(document.getStatus()).isEqualTo(DocumentStatus.READY);
		}

		@Test
		@DisplayName("재색인에 실패하면 문서 FAILED")
		void reindexFailureMarksDocumentFailed() throws Exception {
			DocumentVersion v2 = version(12, 2, "b");
			when(versions.findById(12L)).thenReturn(Optional.of(v2));
			when(ai.index(anyLong(), anyLong(), anyInt())).thenThrow(new AiModuleException("down"));
			document.changeStatus(DocumentStatus.PROCESSING);

			realProcessor.rebuildAfterDelete(DOCUMENT_ID, null, null, 12L);

			assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
		}
	}

	@Nested
	@DisplayName("문서 삭제")
	class DocumentDelete {

		private DocumentService service;

		@BeforeEach
		void setUp() {
			service = new DocumentService(documents, versions, storage, ai, null, null, null);
		}

		@Test
		@DisplayName("문서 행을 지우고 커밋 뒤 벡터 전체와 저장 폴더를 지운다")
		void deletesRowThenVectorsAndFolderAfterCommit() throws Exception {
			DocumentVersion v1 = version(11, 1, "a");
			version(12, 2, "b");

			service.delete(DOCUMENT_ID);

			verify(documents).delete(document);
			verify(ai).deleteIndex(DOCUMENT_ID, null); // keepVersionId = null → 문서의 벡터 전체
			assertThat(uploadDir.resolve(String.valueOf(DOCUMENT_ID))).doesNotExist();
			assertThat(fileOf(v1)).doesNotExist();
		}

		@Test
		@DisplayName("벡터 삭제가 실패해도 삭제는 끝난다")
		void completesEvenIfVectorDeleteFails() {
			when(ai.deleteIndex(DOCUMENT_ID, null)).thenThrow(new AiModuleException("down"));

			service.delete(DOCUMENT_ID);

			verify(documents).delete(document);
		}

		@Test
		@DisplayName("업로드 후처리 중이면 400")
		void processingIs400() {
			document.changeStatus(DocumentStatus.PROCESSING);

			assertThatThrownBy(() -> service.delete(DOCUMENT_ID)).isInstanceOf(DeleteNotAllowedException.class);
			verify(documents, never()).delete(any(Document.class));
		}

		@Test
		@DisplayName("없는 문서면 404")
		void missingDocumentIs404() {
			when(documents.findById(99L)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(DocumentNotFoundException.class);
		}

		@Test
		@DisplayName("저장 폴더가 없어도 오류가 아니다")
		void missingFolderIsNotAnError() {
			storage.deleteDocument(12345L);
		}
	}
}
