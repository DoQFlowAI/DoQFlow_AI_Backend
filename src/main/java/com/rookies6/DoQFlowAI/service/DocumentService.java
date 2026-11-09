package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.request.DocumentListRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentUpdateRequest;
import com.rookies6.DoQFlowAI.dto.response.DocumentDetailResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentSummaryResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentUpdateResponse;
import com.rookies6.DoQFlowAI.dto.response.PageResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import com.rookies6.DoQFlowAI.exception.DeleteNotAllowedException;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서 서비스 < API 8, API 9, API 10, API 11 >
 *
 * 권한 판정은 Backend 개발 명세서 5.2대로 서비스 계층에서 AccessPolicy를 거친다.
 * TODO AccessPolicy가 들어오면 get은 readOrNotFound, update는 writeOrThrow를 이 클래스 안에서 먼저 부른다.
 * TODO create(PermissionService.invite 필요)는 이후 추가한다.
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
		Document document = load(documentId);
		return DocumentDetailResponse.of(document, me.getId(),
				versions.findByDocumentIdOrderByVersionNo(documentId));
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
	 * 문서 삭제 < API 11 > (API 설계서 5.6)
	 *
	 * document 행을 지우면 DB에서 document_version·document_permission이 함께 지워지고(CASCADE),
	 * message_source.version_id는 SET NULL이 되어 이 문서를 근거로 한 과거 답변은 대화 기록에서 가려진다.
	 * 벡터(DELETE /internal/index, keepVersionId = null)와 저장 파일은 커밋이 끝난 뒤에 지운다.
	 * DB 삭제가 롤백됐는데 파일만 사라지는 일을 막기 위해서다. 둘 다 실패해도 로그만 남긴다
	 * (질의응답 scope는 DB에서 계산하므로 남은 벡터는 검색되지 않는다).
	 *
	 * TODO AuditService가 들어오면 DOCUMENT_DELETE 감사 로그를 남긴다.
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
