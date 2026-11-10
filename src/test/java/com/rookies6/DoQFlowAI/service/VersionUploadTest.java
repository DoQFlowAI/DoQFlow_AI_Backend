package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.response.VersionUploadResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.exception.UploadValidationException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

/** 버전 일괄 업로드 동기 부분 단위 테스트 < API 18 > — DB 대신 가짜 Repository, 업로드 폴더는 임시 폴더 */
class VersionUploadTest {

	private static final long DOCUMENT_ID = 7L;

	@TempDir
	Path uploadDir;

	private DocumentRepository documents;
	private DocumentVersionRepository versions;
	private VersionProcessor processor;
	private VersionService service;
	private Document document;
	private final List<DocumentVersion> saved = new ArrayList<>();

	@BeforeEach
	void setUp() {
		documents = mock(DocumentRepository.class);
		versions = mock(DocumentVersionRepository.class);
		processor = mock(VersionProcessor.class);
		UploadFileValidator validator = new UploadFileValidator(10,
				List.of("txt", "md", "csv", "json", "xml", "yaml", "yml", "log"), List.of("pdf", "docx"));
		service = new VersionService(documents, versions, validator, new VersionFileStorage(uploadDir.toString()), processor, null, null,
				1_048_576);

		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		document = Document.builder().name("운영지침").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
		when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

		// save()는 DB처럼 ID를 붙여 돌려준다
		AtomicLong ids = new AtomicLong(100);
		when(versions.save(any(DocumentVersion.class))).thenAnswer(invocation -> {
			DocumentVersion version = invocation.getArgument(0);
			ReflectionTestUtils.setField(version, "id", ids.incrementAndGet());
			saved.add(version);
			return version;
		});
		when(versions.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID)).thenReturn(Optional.empty());
		when(versions.findMaxVersionNo(DOCUMENT_ID)).thenReturn(0);
	}

	private static MockMultipartFile pdf(String name, String body) {
		return new MockMultipartFile("files", name, "application/pdf",
				("%PDF-1.7\n" + body).getBytes(StandardCharsets.UTF_8));
	}

	private static MockMultipartFile text(String name, String body) {
		return new MockMultipartFile("files", name, "text/plain", body.getBytes(StandardCharsets.UTF_8));
	}

	private long storedFileCount() throws Exception {
		if (!Files.exists(uploadDir)) {
			return 0;
		}
		try (Stream<Path> files = Files.walk(uploadDir)) {
			return files.filter(Files::isRegularFile).count();
		}
	}

	@Test
	@DisplayName("첫 업로드는 1번부터 보낸 순서대로 번호를 붙인다")
	void firstUploadNumbersFromOneInOrder() throws Exception {
		VersionUploadResponse response = service.uploadBatch(DOCUMENT_ID,
				List.<MultipartFile>of(pdf("v1.pdf", "a"), pdf("v2.pdf", "b")));

		assertThat(response.currentVersionNo()).isEqualTo(2);
		assertThat(response.versions()).extracting(VersionUploadResponse.UploadedVersion::versionNo)
				.containsExactly(1, 2);
		assertThat(saved).extracting(DocumentVersion::getOriginalFilename).containsExactly("v1.pdf", "v2.pdf");
		assertThat(document.getCurrentVersionNo()).isEqualTo(2);
		assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
		assertThat(storedFileCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("추가 업로드는 기존 최대값 다음부터 이어진다")
	void additionalUploadContinuesFromMax() {
		DocumentVersion v3 = DocumentVersion.builder().document(document).versionNo(3).originalFilename("v3.pdf")
				.storedFilename("old.pdf").mimeType("application/pdf").fileSize(1).contentHash("old-hash").build();
		ReflectionTestUtils.setField(v3, "id", 55L);
		when(versions.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID)).thenReturn(Optional.of(v3));
		when(versions.findMaxVersionNo(DOCUMENT_ID)).thenReturn(3);

		VersionUploadResponse response = service.uploadBatch(DOCUMENT_ID, List.<MultipartFile>of(pdf("v4.pdf", "new")));

		assertThat(response.versions()).extracting(VersionUploadResponse.UploadedVersion::versionNo)
				.containsExactly(4);
		// 직전 버전(3) → 새 첫 버전(4) 구간을 후처리에서 만들도록 직전 버전 ID를 넘긴다
		verify(processor).process(DOCUMENT_ID, 55L, List.of(saved.get(0).getId()));
	}

	@Test
	@DisplayName("텍스트 계열은 본문을 바로 채워 READY 문서 계열은 PROCESSING")
	void textReadyImmediatelyDocumentProcessing() {
		VersionUploadResponse response = service.uploadBatch(DOCUMENT_ID,
				List.<MultipartFile>of(text("v1.md", "1. 목적\r\n이 지침은"), pdf("v2.pdf", "b")));

		assertThat(response.versions()).extracting(VersionUploadResponse.UploadedVersion::status)
				.containsExactly(VersionStatus.READY, VersionStatus.PROCESSING);
		assertThat(saved.get(0).getContentText()).isEqualTo("1. 목적\r\n이 지침은"); // 줄바꿈 그대로
		assertThat(saved.get(0).getMimeType()).isEqualTo("text/markdown");
		assertThat(saved.get(1).getContentText()).isNull();
	}

	@Test
	@DisplayName("저장한 뒤 후처리에 새 버전 ID를 넘긴다")
	void passesNewVersionIdsToProcessing() {
		service.uploadBatch(DOCUMENT_ID, List.<MultipartFile>of(pdf("v1.pdf", "a"), pdf("v2.pdf", "b")));

		verify(processor).process(DOCUMENT_ID, null, List.of(saved.get(0).getId(), saved.get(1).getId()));
	}

	@Test
	@DisplayName("검증에 실패하면 아무것도 저장하지 않는다")
	void savesNothingOnValidationFailure() throws Exception {
		assertThatThrownBy(() -> service.uploadBatch(DOCUMENT_ID,
				List.<MultipartFile>of(pdf("v1.pdf", "a"), text("v2.html", "<p>x</p>"))))
				.isInstanceOf(UploadValidationException.class);

		assertThat(saved).isEmpty();
		assertThat(storedFileCount()).isZero();
		verify(processor, never()).process(any(), any(), anyList());
	}

	@Test
	@DisplayName("직전 버전과 같은 파일이면 400")
	void sameAsPreviousIs400() {
		MockMultipartFile same = pdf("v2.pdf", "same");
		String hash = new UploadFileValidator(10, List.of(), List.of("pdf"))
				.validate(List.of(same), null).get(0).contentHash();
		DocumentVersion v1 = DocumentVersion.builder().document(document).versionNo(1).originalFilename("v1.pdf")
				.storedFilename("old.pdf").mimeType("application/pdf").fileSize(1).contentHash(hash).build();
		when(versions.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID)).thenReturn(Optional.of(v1));

		assertThatThrownBy(() -> service.uploadBatch(DOCUMENT_ID, List.<MultipartFile>of(same)))
				.isInstanceOf(UploadValidationException.class)
				.extracting(e -> ((UploadValidationException) e).getReason())
				.isEqualTo(UploadValidationException.Reason.DUPLICATE_CONTENT);
	}

	@Test
	@DisplayName("저장 도중 실패하면 이미 저장한 파일을 지운다")
	void removesStoredFilesOnFailure() throws Exception {
		when(versions.save(any(DocumentVersion.class)))
				.thenAnswer(invocation -> invocation.getArgument(0))
				.thenThrow(new IllegalStateException("db down"));

		assertThatThrownBy(() -> service.uploadBatch(DOCUMENT_ID,
				List.<MultipartFile>of(pdf("v1.pdf", "a"), pdf("v2.pdf", "b"))))
				.isInstanceOf(IllegalStateException.class);

		assertThat(storedFileCount()).isZero();
		verify(processor, never()).process(any(), any(), anyList());
	}

	@Test
	@DisplayName("후처리 대기열이 가득 차면 문서를 FAILED로 둔다")
	void fullQueueMarksDocumentFailed() {
		doThrow(new TaskRejectedException("queue full")).when(processor).process(any(), any(), anyList());

		service.uploadBatch(DOCUMENT_ID, List.<MultipartFile>of(pdf("v1.pdf", "a")));

		ArgumentCaptor<Long> documentId = ArgumentCaptor.forClass(Long.class);
		verify(processor).markFailed(documentId.capture());
		assertThat(documentId.getValue()).isEqualTo(DOCUMENT_ID);
	}
}
