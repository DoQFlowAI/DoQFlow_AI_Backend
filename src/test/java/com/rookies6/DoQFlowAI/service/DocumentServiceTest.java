package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.request.DocumentListRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentUpdateRequest;
import com.rookies6.DoQFlowAI.dto.response.DocumentDetailResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentSummaryResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentUpdateResponse;
import com.rookies6.DoQFlowAI.dto.response.PageResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

/** 문서 목록·단건·수정 단위 테스트 < API 8, API 9, API 10 > — DB 대신 가짜 Repository를 쓴다 */
class DocumentServiceTest {

	private DocumentRepository documents;
	private DocumentVersionRepository versions;
	private DocumentService service;
	private AppUser lead;
	private AppUser staff;

	@BeforeEach
	void setUp() {
		documents = mock(DocumentRepository.class);
		versions = mock(DocumentVersionRepository.class);
		service = new DocumentService(documents, versions, null, null, org.mockito.Mockito.mock(com.rookies6.DoQFlowAI.security.AccessPolicy.class));

		lead = user(1L, "lead@example.com", "팀장", 2);
		staff = user(2L, "staff@example.com", "사원", 1);
	}

	private static AppUser user(long id, String email, String name, int rankLevel) {
		AppUser user = AppUser.builder().email(email).passwordHash("x").name(name).rankLevel(rankLevel).build();
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	private static Document document(long id, AppUser owner, String name) {
		Document document = Document.builder().name(name).description("설명").uploadedBy(owner).build();
		ReflectionTestUtils.setField(document, "id", id);
		return document;
	}

	@Test
	@DisplayName("목록은 요청한 페이지로 조회하고 내가 등록한 문서만 owned")
	void listUsesRequestedPageAndMarksOwned() {
		Document mine = document(7L, lead, "배포 가이드");
		Document invited = document(8L, staff, "운영지침");
		when(documents.findAccessible(eq(1L), any(Pageable.class)))
				.thenAnswer(invocation -> new PageImpl<>(List.of(mine, invited), invocation.getArgument(1), 2));

		PageResponse<DocumentSummaryResponse> response =
				service.list(lead, new DocumentListRequest(0, 20, "createdAt,asc"));

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(documents).findAccessible(eq(1L), pageable.capture());
		assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt")));

		assertThat(response.totalElements()).isEqualTo(2);
		assertThat(response.size()).isEqualTo(20);
		assertThat(response.content()).extracting(DocumentSummaryResponse::owned).containsExactly(true, false);
		assertThat(response.content().get(1).uploadedBy())
				.isEqualTo(new DocumentSummaryResponse.UploadedBy("사원", 1));
	}

	@Test
	@DisplayName("목록 기본값은 0페이지 10개 최신순")
	void listDefaultsToFirstPageTenNewest() {
		when(documents.findAccessible(eq(1L), any(Pageable.class))).thenReturn(Page.empty());

		service.list(lead, new DocumentListRequest(null, null, null));

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(documents).findAccessible(eq(1L), pageable.capture());
		assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
	}

	@Test
	@DisplayName("단건은 등록자 이메일과 버전 목록을 함께 내린다")
	void detailIncludesUploaderEmailAndVersions() {
		Document doc = document(7L, lead, "배포 가이드");
		DocumentVersion v1 = DocumentVersion.builder().document(doc).versionNo(1).originalFilename("v1.pdf")
				.storedFilename("uuid.pdf").mimeType("application/pdf").fileSize(1).contentHash("h").build();
		when(documents.findById(7L)).thenReturn(Optional.of(doc));
		when(versions.findByDocumentIdOrderByVersionNo(7L)).thenReturn(List.of(v1));

		DocumentDetailResponse response = service.get(staff, 7L);

		assertThat(response.owned()).isFalse();
		assertThat(response.uploadedBy())
				.isEqualTo(new DocumentDetailResponse.UploadedBy("팀장", "lead@example.com", 2));
		assertThat(response.versions()).extracting(DocumentDetailResponse.VersionItem::originalFilename)
				.containsExactly("v1.pdf");
	}

	@Test
	@DisplayName("없는 문서면 404 DOCUMENT_NOT_FOUND")
	void missingDocumentIs404() {
		when(documents.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(lead, 99L))
				.isInstanceOf(DocumentNotFoundException.class)
				.extracting(e -> ((DocumentNotFoundException) e).getErrorCode())
				.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
		assertThatThrownBy(() -> service.update(99L, new DocumentUpdateRequest("새 이름", null)))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	@DisplayName("수정은 보낸 필드만 바꾸고 flush한 뒤 응답한다")
	void updateChangesSentFieldsAndFlushes() {
		Document doc = document(7L, lead, "배포 가이드");
		when(documents.findById(7L)).thenReturn(Optional.of(doc));

		DocumentUpdateResponse response = service.update(7L, new DocumentUpdateRequest("배포 가이드 v2", null));

		assertThat(response.name()).isEqualTo("배포 가이드 v2");
		assertThat(response.description()).isEqualTo("설명"); // 보내지 않은 필드는 그대로
		verify(documents).flush(); // @PreUpdate로 updatedAt이 바뀐 뒤 응답
	}

	@Test
	@DisplayName("설명은 빈 문자열로 지울 수 있다")
	void descriptionCanBeClearedWithEmptyString() {
		Document doc = document(7L, lead, "배포 가이드");
		when(documents.findById(7L)).thenReturn(Optional.of(doc));

		DocumentUpdateResponse response = service.update(7L, new DocumentUpdateRequest(null, ""));

		assertThat(response.name()).isEqualTo("배포 가이드");
		assertThat(response.description()).isEmpty();
	}
}
