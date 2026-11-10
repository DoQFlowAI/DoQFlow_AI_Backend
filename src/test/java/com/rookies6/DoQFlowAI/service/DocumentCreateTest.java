package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.request.DocumentCreateRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentUpdateRequest;
import com.rookies6.DoQFlowAI.dto.response.DocumentCreateResponse;
import com.rookies6.DoQFlowAI.dto.response.InviteResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.exception.ForbiddenException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 문서 생성 + 초대 < API 7 >, 문서 수정·삭제 감사 로그 < API 10, API 11 >
 * 초대 규칙(미가입·재초대·등록자 자신)은 PermissionService가 맡으므로 여기서는 호출과 응답만 본다.
 */
class DocumentCreateTest {

	private static final long DOCUMENT_ID = 31L;

	private DocumentRepository documents;
	private AccessPolicy accessPolicy;
	private PermissionService permissionService;
	private AuditService audit;
	private DocumentService service;
	private AppUser lead;

	@BeforeEach
	void setUp() {
		documents = mock(DocumentRepository.class);
		accessPolicy = mock(AccessPolicy.class);
		permissionService = mock(PermissionService.class);
		audit = mock(AuditService.class);
		service = new DocumentService(documents, null, null, null, accessPolicy, permissionService, audit);

		lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		ReflectionTestUtils.setField(lead, "id", 1L);
		when(documents.save(any(Document.class))).thenAnswer(inv -> {
			Document saved = inv.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", DOCUMENT_ID);
			return saved;
		});
	}

	@Test
	@DisplayName("팀장이 만들면 빈 문서를 저장하고 초대 결과를 그대로 응답한다")
	void leadCreatesEmptyDocumentAndReturnsInviteResult() {
		when(permissionService.invite(eq(1L), eq(DOCUMENT_ID), anyList()))
				.thenReturn(new InviteResponse(2, List.of("nobody@example.com")));

		DocumentCreateResponse response = service.create(lead, new DocumentCreateRequest(
				"출장비 규정", "설명", List.of("a@example.com", "b@example.com", "nobody@example.com")));

		ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
		verify(documents).save(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo("출장비 규정");
		assertThat(saved.getValue().getUploadedBy()).isSameAs(lead);

		assertThat(response.documentId()).isEqualTo(DOCUMENT_ID);
		assertThat(response.currentVersionNo()).isZero();
		assertThat(response.status()).isEqualTo(DocumentStatus.EMPTY);
		assertThat(response.invited()).isEqualTo(2);
		assertThat(response.notFound()).containsExactly("nobody@example.com");
	}

	@Test
	@DisplayName("초대 이메일은 소문자로 바꾸고 중복을 지워 넘긴다")
	void passesLowercasedDistinctEmailsToInvite() {
		when(permissionService.invite(anyLong(), anyLong(), anyList())).thenReturn(new InviteResponse(1, List.of()));

		service.create(lead, new DocumentCreateRequest("문서", null,
				List.of("Staff@Example.com", "staff@example.com")));

		verify(permissionService).invite(1L, DOCUMENT_ID, List.of("staff@example.com"));
	}

	@Test
	@DisplayName("초대 목록이 없으면 빈 목록으로 부르고 초대 0명")
	void noInviteesInvitesNobody() {
		when(permissionService.invite(anyLong(), anyLong(), anyList())).thenReturn(new InviteResponse(0, List.of()));

		DocumentCreateResponse response = service.create(lead, new DocumentCreateRequest("문서", null, null));

		verify(permissionService).invite(1L, DOCUMENT_ID, List.of());
		assertThat(response.invited()).isZero();
		assertThat(response.notFound()).isEmpty();
	}

	@Test
	@DisplayName("사원이면 403이고 문서를 저장하지 않는다")
	void staffIs403AndSavesNothing() {
		doThrow(new ForbiddenException("rankLevel=1 cannot register")).when(accessPolicy).registerOrThrow(1L);

		assertThatThrownBy(() -> service.create(lead, new DocumentCreateRequest("문서", null, null)))
				.isInstanceOf(ForbiddenException.class);
		verify(documents, never()).save(any());
		verify(permissionService, never()).invite(anyLong(), anyLong(), anyList());
		verify(audit, never()).log(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("생성 감사 로그에는 초대 수만 남기고 이메일은 남기지 않는다")
	void createAuditHasCountsWithoutEmails() {
		when(permissionService.invite(anyLong(), anyLong(), anyList()))
				.thenReturn(new InviteResponse(1, List.of("nobody@example.com")));

		service.create(lead, new DocumentCreateRequest("문서", null, List.of("a@example.com", "nobody@example.com")));

		ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
		verify(audit).log(eq(AuditEventType.DOCUMENT_CREATE), eq(AuditResult.SUCCESS), eq(1L), eq("DOCUMENT"),
				eq(DOCUMENT_ID), detail.capture());
		assertThat(detail.getValue()).isEqualTo("invited=1 notFound=1").doesNotContain("@");
	}

	@Test
	@DisplayName("수정 감사 로그에는 바뀐 필드 이름만 남긴다")
	void updateAuditHasFieldNamesOnly() {
		Document document = Document.builder().name("이전").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
		when(documents.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));

		service.update(lead, DOCUMENT_ID, new DocumentUpdateRequest("새 이름", "새 설명"));

		verify(accessPolicy).writeOrThrow(1L, DOCUMENT_ID);
		verify(audit).log(AuditEventType.DOCUMENT_UPDATE, AuditResult.SUCCESS, 1L, "DOCUMENT", DOCUMENT_ID,
				"fields=name,description");
	}

	@Test
	@DisplayName("수정 권한이 없으면 감사 로그를 남기지 않는다")
	void updateWithoutPermissionLeavesNoAudit() {
		doThrow(new ForbiddenException("not the uploader")).when(accessPolicy).writeOrThrow(1L, DOCUMENT_ID);

		assertThatThrownBy(() -> service.update(lead, DOCUMENT_ID, new DocumentUpdateRequest("새 이름", null)))
				.isInstanceOf(ForbiddenException.class);
		verify(audit, never()).log(any(), any(), any(), any(), any(), any());
	}
}
