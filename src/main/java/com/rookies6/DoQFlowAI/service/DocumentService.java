package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.request.DocumentCreateRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentListRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentUpdateRequest;
import com.rookies6.DoQFlowAI.dto.response.DocumentCreateResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentDetailResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentSummaryResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentUpdateResponse;
import com.rookies6.DoQFlowAI.dto.response.InviteResponse;
import com.rookies6.DoQFlowAI.dto.response.PageResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import com.rookies6.DoQFlowAI.exception.DeleteNotAllowedException;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서 서비스 < API 7, API 8, API 9, API 10, API 11 >
 *
 * 권한 판정은 Backend 개발 명세서 5.2대로 서비스 계층에서 AccessPolicy를 거친다.
 * 컨트롤러는 AppUser를 받는 메서드만 부른다. 이 메서드들이 트랜잭션 안에서 AccessPolicy를 먼저 호출한다
 *   등록 registerOrThrow - 팀장이 아니면 403
 *   읽기 readOrNotFound - 없는 문서·권한 밖 모두 404
 *   쓰기 writeOrThrow   - 읽을 수 없으면 404, 읽을 수는 있으나 등록자가 아니면 403
 * documentId만 받는 메서드는 권한 판정을 통과한 뒤의 처리다 (단위·통합 테스트도 이것을 쓴다).
 *
 * 감사 로그 — DOCUMENT_CREATE·UPDATE·DELETE (DB 설계서 3.3). 이메일 원문은 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentService {

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;
	private final VersionFileStorage storage;
	private final AiClientService aiClient;
	private final AccessPolicy accessPolicy;
	private final PermissionService permissionService;
	private final AuditService audit;

	/**
	 * 문서 생성 + 초대 < API 7 > (API 설계서 5.2) — 팀장만. 201
	 *
	 * 문서를 만들고 같은 트랜잭션에서 초대한다 (Backend 개발 명세서 4.3). 파일은 버전 업로드(API 18)로 따로 올린다.
	 * 초대는 PermissionService.invite를 그대로 쓴다: 미가입 이메일은 오류가 아니라 notFound로 돌려주고,
	 * 등록자 자신과 이미 PENDING·ACCEPTED인 사람은 건너뛰며, 초대 건당 PERMISSION_GRANT를 남긴다.
	 * 이메일은 요청에서 소문자로 바꾸고 중복을 지운 뒤 넘긴다 (최대 50개는 요청 검증).
	 *
	 * @throws com.rookies6.DoQFlowAI.exception.ForbiddenException 사원 (403)
	 */
	@Transactional
	public DocumentCreateResponse create(AppUser me, DocumentCreateRequest request) {
		accessPolicy.registerOrThrow(me.getId());
		Document document = documents.save(Document.builder()
				.name(request.name())
				.description(request.description())
				.uploadedBy(me)
				.build());

		InviteResponse invite = permissionService.invite(me.getId(), document.getId(),
				request.distinctInviteeEmails());

		audit.log(AuditEventType.DOCUMENT_CREATE, AuditResult.SUCCESS, me.getId(), "DOCUMENT", document.getId(),
				"invited=" + invite.invited() + " notFound=" + invite.notFound().size());
		return DocumentCreateResponse.of(document, invite.invited(), invite.notFound());
	}

	/**
	 * 문서 목록 < API 8 >
	 *
	 * 내가 등록한 문서 + 초대를 수락(ACCEPTED)한 문서만 나온다. PENDING·REJECTED는 빠진다.
	 * 쿼리 조건 자체가 권한 필터이므로 totalElements도 필터 뒤의 개수
	 * owned = true인 문서에만 화면이 업로드·초대 버튼을 띄운다.
	 */
	public PageResponse<DocumentSummaryResponse> list(AppUser me, DocumentListRequest request) {
		return PageResponse.of(
				documents.findAccessible(me.getId(), request.toPageable()),
				document -> DocumentSummaryResponse.of(document, me.getId()));
	}

	/**
	 * 문서 단건 < API 9 >
	 *
	 * 등록자 이름·이메일·직급과 버전 목록을 내려보낸다. 열람자 목록은 넣지 않는다 (API 13).
	 * 이 트랜잭션 안에서 다시 읽어, 등록자(LAZY)를 응답에 담을 수 있게 한다.
	 *
	 * @param documentId 읽기 권한 판정을 통과한 문서
	 * @throws DocumentNotFoundException 없는 문서 (404, 권한 밖과 같은 응답)
	 */
	public DocumentDetailResponse get(AppUser me, Long documentId) {
		accessPolicy.readOrNotFound(me.getId(), documentId);
		Document document = load(documentId);
		return DocumentDetailResponse.of(document, me.getId(),
				versions.findByDocumentIdOrderByVersionNo(documentId));
	}

	/**
	 * 문서 이름·설명 수정 < API 10 > - 등록자 본인만
	 *
	 * @throws DocumentNotFoundException 없는 문서·읽을 수 없는 문서 (404)
	 * @throws com.rookies6.DoQFlowAI.exception.ForbiddenException 등록자가 아님 (403)
	 */
	@Transactional
	public DocumentUpdateResponse update(AppUser me, Long documentId, DocumentUpdateRequest request) {
		accessPolicy.writeOrThrow(me.getId(), documentId);
		DocumentUpdateResponse response = update(documentId, request);
		audit.log(AuditEventType.DOCUMENT_UPDATE, AuditResult.SUCCESS, me.getId(), "DOCUMENT", documentId,
				"fields=" + changedFields(request));
		return response;
	}

	// 바뀐 값이 아니라 필드 이름만 남긴다
	private static String changedFields(DocumentUpdateRequest request) {
		List<String> fields = new ArrayList<>(2);
		if (request.name() != null) {
			fields.add("name");
		}
		if (request.description() != null) {
			fields.add("description");
		}
		return String.join(",", fields);
	}

	/**
	 * 문서 이름·설명 수정 < API 10 >
	 *
	 * 보낸 필드만 바꾼다. 둘 다 없으면 요청 검증에서 400이 된다 (DocumentUpdateRequest).
	 * 버전·열람 권한·색인에는 영향이 없다. 답변 당시 스냅샷인 message_source.document_name도 그대로 둔다.
	 * updatedAt은 @PreUpdate로 갱신되므로 flush한 뒤 응답을 만든다.
	 *
	 *
	 * @param documentId 쓰기 권한 판정(등록자 본인)을 통과한 문서
	 * @throws DocumentNotFoundException 없는 문서 (404)
	 */
	@Transactional
	public DocumentUpdateResponse update(Long documentId, DocumentUpdateRequest request) {
		Document document = load(documentId);
		document.update(request.name(), request.description());
		documents.flush();
		return DocumentUpdateResponse.from(document);
	}

	/**
	 * 문서 삭제 < API 11 > - 등록자 본인만
	 *
	 * @throws DocumentNotFoundException 없는 문서·읽을 수 없는 문서 (404)
	 * @throws com.rookies6.DoQFlowAI.exception.ForbiddenException 등록자가 아님 (403)
	 */
	@Transactional
	public void delete(AppUser me, Long documentId) {
		accessPolicy.writeOrThrow(me.getId(), documentId);
		delete(documentId);
		audit.log(AuditEventType.DOCUMENT_DELETE, AuditResult.SUCCESS, me.getId(), "DOCUMENT", documentId, null);
	}

	/**
	 * 문서 삭제 < API 11 > (API 설계서 5.6)
	 *
	 * document 행을 지우면 DB에서 document_version·document_permission이 함께 지워지고(CASCADE),
	 * message_source.version_id는 SET NULL이 되어 이 문서를 근거로 한 과거 답변은 대화 기록에서 가려진다.
	 * 벡터(DELETE /internal/index, keepVersionId = null)와 저장 파일은 커밋이 끝난 뒤에 지운다.
	 * DB 삭제가 롤백됐는데 파일만 사라지는 일을 막기 위해서다. 둘 다 실패해도 로그만 남긴다
	 * (질의응답 scope는 DB에서 계산하므로 남은 벡터는 검색되지 않는다).
	 *
	 * @param documentId 쓰기 권한 판정(등록자 본인)을 통과한 문서
	 * @throws DocumentNotFoundException 없는 문서 (404)
	 * @throws DeleteNotAllowedException 업로드 후처리 중 (400)
	 */
	@Transactional
	public void delete(Long documentId) {
		Document document = load(documentId);
		if (document.getStatus() == DocumentStatus.PROCESSING) {
			throw new DeleteNotAllowedException("document is processing: documentId=" + documentId);
		}
		documents.delete(document);
		documents.flush(); // FK 문제 등을 여기서 드러낸다

		TransactionHooks.afterCommit(() -> {
			try {
				aiClient.deleteIndex(documentId, null);
			} catch (AiModuleException e) {
				log.warn("vectors were not removed: documentId={}", documentId);
			}
			try {
				storage.deleteDocument(documentId);
			} catch (RuntimeException e) {
				log.warn("stored files were not removed: documentId={}", documentId);
			}
		});
	}

	private Document load(Long documentId) {
		return documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
	}
}
