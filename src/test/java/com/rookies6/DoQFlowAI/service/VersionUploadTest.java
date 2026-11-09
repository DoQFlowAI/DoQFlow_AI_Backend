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

	private DocumentVersionRepository versions;
	private VersionProcessor processor;
	private VersionService service;
	private Document document;
	private final List<DocumentVersion> saved = new ArrayList<>();

	@BeforeEach
	void setUp() {
		versions = mock(DocumentVersionRepository.class);
		processor = mock(VersionProcessor.class);
		UploadFileValidator validator = new UploadFileValidator(10,
				List.of("txt", "md", "csv", "json", "xml", "yaml", "yml", "log"), List.of("pdf", "docx"));
		service = new VersionService(null, versions, validator, new VersionFileStorage(uploadDir.toString()), processor,
				1_048_576);

		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		document = Document.builder().name("운영지침").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);

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
	void 첫_업로드는_1번부터_보낸_순서대로_번호를_붙인다() throws Exception {
		VersionUploadResponse response = service.uploadBatch(document,
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
	void 추가_업로드는_기존_최대값_다음부터_이어진다() {
		DocumentVersion v3 = DocumentVersion.builder().document(document).versionNo(3).originalFilename("v3.pdf")
				.storedFilename("old.pdf").mimeType("application/pdf").fileSize(1).contentHash("old-hash").build();
		ReflectionTestUtils.setField(v3, "id", 55L);
		when(versions.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID)).thenReturn(Optional.of(v3));
		when(versions.findMaxVersionNo(DOCUMENT_ID)).thenReturn(3);

		VersionUploadResponse response = service.uploadBatch(document, List.<MultipartFile>of(pdf("v4.pdf", "new")));

		assertThat(response.versions()).extracting(VersionUploadResponse.UploadedVersion::versionNo)
				.containsExactly(4);
		// 직전 버전(3) → 새 첫 버전(4) 구간을 후처리에서 만들도록 직전 버전 ID를 넘긴다
		verify(processor).process(DOCUMENT_ID, 55L, List.of(saved.get(0).getId()));
	}

	@Test
	void 텍스트_계열은_본문을_바로_채워_READY_문서_계열은_PROCESSING() {
		VersionUploadResponse response = service.uploadBatch(document,
				List.<MultipartFile>of(text("v1.md", "1. 목적\r\n이 지침은"), pdf("v2.pdf", "b")));

		assertThat(response.versions()).extracting(VersionUploadResponse.UploadedVersion::status)
				.containsExactly(VersionStatus.READY, VersionStatus.PROCESSING);
		assertThat(saved.get(0).getContentText()).isEqualTo("1. 목적\r\n이 지침은"); // 줄바꿈 그대로
		assertThat(saved.get(0).getMimeType()).isEqualTo("text/markdown");
		assertThat(saved.get(1).getContentText()).isNull();
	}

	@Test
	void 저장한_뒤_후처리에_새_버전_ID를_넘긴다() {
		service.uploadBatch(document, List.<MultipartFile>of(pdf("v1.pdf", "a"), pdf("v2.pdf", "b")));

		verify(processor).process(DOCUMENT_ID, null, List.of(saved.get(0).getId(), saved.get(1).getId()));
	}

	@Test
	void 검증에_실패하면_아무것도_저장하지_않는다() throws Exception {
		assertThatThrownBy(() -> service.uploadBatch(document,
				List.<MultipartFile>of(pdf("v1.pdf", "a"), text("v2.html", "<p>x</p>"))))
				.isInstanceOf(UploadValidationException.class);

		assertThat(saved).isEmpty();
		assertThat(storedFileCount()).isZero();
		verify(processor, never()).process(any(), any(), anyList());
	}

	@Test
	void 직전_버전과_같은_파일이면_400() {
		MockMultipartFile same = pdf("v2.pdf", "same");
		String hash = new UploadFileValidator(10, List.of(), List.of("pdf"))
				.validate(List.of(same), null).get(0).contentHash();
		DocumentVersion v1 = DocumentVersion.builder().document(document).versionNo(1).originalFilename("v1.pdf")
				.storedFilename("old.pdf").mimeType("application/pdf").fileSize(1).contentHash(hash).build();
		when(versions.findTopByDocumentIdOrderByVersionNoDesc(DOCUMENT_ID)).thenReturn(Optional.of(v1));

		assertThatThrownBy(() -> service.uploadBatch(document, List.<MultipartFile>of(same)))
				.isInstanceOf(UploadValidationException.class)
				.extracting(e -> ((UploadValidationException) e).getReason())
				.isEqualTo(UploadValidationException.Reason.DUPLICATE_CONTENT);
	}

	@Test
	void 저장_도중_실패하면_이미_저장한_파일을_지운다() throws Exception {
		when(versions.save(any(DocumentVersion.class)))
				.thenAnswer(invocation -> invocation.getArgument(0))
				.thenThrow(new IllegalStateException("db down"));

		assertThatThrownBy(() -> service.uploadBatch(document,
				List.<MultipartFile>of(pdf("v1.pdf", "a"), pdf("v2.pdf", "b"))))
				.isInstanceOf(IllegalStateException.class);

		assertThat(storedFileCount()).isZero();
		verify(processor, never()).process(any(), any(), anyList());
	}

	@Test
	void 후처리_대기열이_가득_차면_문서를_FAILED로_둔다() {
		doThrow(new TaskRejectedException("queue full")).when(processor).process(any(), any(), anyList());

		service.uploadBatch(document, List.<MultipartFile>of(pdf("v1.pdf", "a")));

		ArgumentCaptor<Long> documentId = ArgumentCaptor.forClass(Long.class);
		verify(processor).markFailed(documentId.capture());
		assertThat(documentId.getValue()).isEqualTo(DOCUMENT_ID);
	}
}
