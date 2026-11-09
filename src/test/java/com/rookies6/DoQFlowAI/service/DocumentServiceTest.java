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
		service = new DocumentService(documents, versions);

		lead = user(1L, "lead@example.com", "홍서연", 2);
		staff = user(2L, "staff@example.com", "김정현", 1);
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
	void 목록은_요청한_페이지로_조회하고_내가_등록한_문서만_owned() {
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
				.isEqualTo(new DocumentSummaryResponse.UploadedBy("김정현", 1));
	}

	@Test
	void 목록_기본값은_0페이지_10개_최신순() {
		when(documents.findAccessible(eq(1L), any(Pageable.class))).thenReturn(Page.empty());

		service.list(lead, new DocumentListRequest(null, null, null));

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(documents).findAccessible(eq(1L), pageable.capture());
		assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
	}

	@Test
	void 단건은_등록자_이메일과_버전_목록을_함께_내린다() {
		Document doc = document(7L, lead, "배포 가이드");
		DocumentVersion v1 = DocumentVersion.builder().document(doc).versionNo(1).originalFilename("v1.pdf")
				.storedFilename("uuid.pdf").mimeType("application/pdf").fileSize(1).contentHash("h").build();
		when(documents.findById(7L)).thenReturn(Optional.of(doc));
		when(versions.findByDocumentIdOrderByVersionNo(7L)).thenReturn(List.of(v1));

		DocumentDetailResponse response = service.get(staff, 7L);

		assertThat(response.owned()).isFalse();
		assertThat(response.uploadedBy())
				.isEqualTo(new DocumentDetailResponse.UploadedBy("홍서연", "lead@example.com", 2));
		assertThat(response.versions()).extracting(DocumentDetailResponse.VersionItem::originalFilename)
				.containsExactly("v1.pdf");
	}

	@Test
	void 없는_문서면_404_DOCUMENT_NOT_FOUND() {
		when(documents.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(lead, 99L))
				.isInstanceOf(DocumentNotFoundException.class)
				.extracting(e -> ((DocumentNotFoundException) e).getErrorCode())
				.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
		assertThatThrownBy(() -> service.update(99L, new DocumentUpdateRequest("새 이름", null)))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void 수정은_보낸_필드만_바꾸고_flush한_뒤_응답한다() {
		Document doc = document(7L, lead, "배포 가이드");
		when(documents.findById(7L)).thenReturn(Optional.of(doc));

		DocumentUpdateResponse response = service.update(7L, new DocumentUpdateRequest("배포 가이드 v2", null));

		assertThat(response.name()).isEqualTo("배포 가이드 v2");
		assertThat(response.description()).isEqualTo("설명"); // 보내지 않은 필드는 그대로
		verify(documents).flush(); // @PreUpdate로 updatedAt이 바뀐 뒤 응답
	}

	@Test
	void 설명은_빈_문자열로_지울_수_있다() {
		Document doc = document(7L, lead, "배포 가이드");
		when(documents.findById(7L)).thenReturn(Optional.of(doc));

		DocumentUpdateResponse response = service.update(7L, new DocumentUpdateRequest(null, ""));

		assertThat(response.name()).isEqualTo("배포 가이드");
		assertThat(response.description()).isEmpty();
	}
}
