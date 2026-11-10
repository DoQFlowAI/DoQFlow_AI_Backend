package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.exception.ForbiddenException;
import com.rookies6.DoQFlowAI.exception.UploadValidationException;
import com.rookies6.DoQFlowAI.exception.UploadValidationException.Reason;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 버전 업로드 거부·삭제 감사 로그 < API 18, API 21 >
 * 성공 업로드(VERSION_UPLOAD)와 삭제(VERSION_DELETE)는 실제 DB 통합 테스트에서 확인한다.
 */
class VersionAuditTest {

	private static final long DOCUMENT_ID = 7L;

	private DocumentRepository documents;
	private DocumentVersionRepository versions;
	private UploadFileValidator validator;
	private AccessPolicy accessPolicy;
	private AuditService audit;
	private VersionService service;
	private AppUser lead;
	private List<MultipartFile> files;

	@BeforeEach
	void setUp() {
		documents = mock(DocumentRepository.class);
		versions = mock(DocumentVersionRepository.class);
		validator = mock(UploadFileValidator.class);
		accessPolicy = mock(AccessPolicy.class);
		audit = mock(AuditService.class);
		service = new VersionService(documents, versions, validator, null, null, accessPolicy, audit, 1_048_576);

		lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		ReflectionTestUtils.setField(lead, "id", 1L);
		files = List.of(
				new MockMultipartFile("files", "급여_기밀.exe", "application/octet-stream", new byte[] {1}),
				new MockMultipartFile("files", "v2.txt", "text/plain", "본문".getBytes(StandardCharsets.UTF_8)));
	}

	private String failedDetail(Long expectedTargetId) {
		ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
		verify(audit).log(eq(AuditEventType.VERSION_UPLOAD_FAILED), eq(AuditResult.FAILURE), eq(1L),
				eq(expectedTargetId == null ? null : "DOCUMENT"), eq(expectedTargetId), detail.capture());
		return detail.getValue();
	}

	@Test
	@DisplayName("파일 검증에 걸리면 실패 단계만 남기고 원본 파일명은 남기지 않는다")
	void validationFailureLogsReasonWithoutFilename() {
		Document document = Document.builder().name("문서").uploadedBy(lead).build();
		when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
		when(validator.validate(anyList(), any())).thenThrow(new UploadValidationException(Reason.INVALID_TYPE));

		assertThatThrownBy(() -> service.uploadBatch(lead, DOCUMENT_ID, files))
				.isInstanceOf(UploadValidationException.class);

		assertThat(failedDetail(DOCUMENT_ID)).isEqualTo("fileCount=2 reason=INVALID_TYPE")
				.doesNotContain("급여", ".exe", "v2.txt");
		verify(audit, never()).log(eq(AuditEventType.VERSION_UPLOAD), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("등록자가 아니면 NOT_OWNER로 남기고 403을 그대로 던진다")
	void notOwnerIsLogged() {
		doThrow(new ForbiddenException("not the uploader")).when(accessPolicy).writeOrThrow(1L, DOCUMENT_ID);

		assertThatThrownBy(() -> service.uploadBatch(lead, DOCUMENT_ID, files)).isInstanceOf(ForbiddenException.class);

		assertThat(failedDetail(DOCUMENT_ID)).isEqualTo("fileCount=2 reason=NOT_OWNER");
	}

	@Test
	@DisplayName("읽을 수 없는 문서면 NOT_FOUND로 남기고 대상 문서는 비운다")
	void notFoundHidesTarget() {
		doThrow(new DocumentNotFoundException(DOCUMENT_ID)).when(accessPolicy).writeOrThrow(1L, DOCUMENT_ID);

		assertThatThrownBy(() -> service.uploadBatch(lead, DOCUMENT_ID, files))
				.isInstanceOf(DocumentNotFoundException.class);

		assertThat(failedDetail(null)).isEqualTo("fileCount=2 reason=NOT_FOUND");
	}

	@Test
	@DisplayName("files 없이 오면 파일 수 0으로 남긴다")
	void missingFilesCountAsZero() {
		Document document = Document.builder().name("문서").uploadedBy(lead).build();
		when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
		when(validator.validate(any(), any())).thenThrow(new UploadValidationException(Reason.FILE_COUNT));

		assertThatThrownBy(() -> service.uploadBatch(lead, DOCUMENT_ID, List.of()))
				.isInstanceOf(UploadValidationException.class);

		assertThat(failedDetail(DOCUMENT_ID)).isEqualTo("fileCount=0 reason=FILE_COUNT");
	}

	@Test
	@DisplayName("버전 삭제가 거부되면 VERSION_DELETE를 남기지 않는다")
	void rejectedDeleteIsNotLogged() {
		Document document = Document.builder().name("문서").uploadedBy(lead).build();
		when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
		when(versions.findByDocumentIdOrderByVersionNo(DOCUMENT_ID)).thenReturn(List.of());

		assertThatThrownBy(() -> service.deleteVersion(lead, DOCUMENT_ID, 9))
				.isInstanceOf(VersionNotFoundException.class);

		verify(audit, never()).log(any(), any(), any(), any(), any(), any());
	}
}
