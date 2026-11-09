package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.request.DocumentListRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentUpdateRequest;
import com.rookies6.DoQFlowAI.dto.response.DocumentDetailResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentSummaryResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentUpdateResponse;
import com.rookies6.DoQFlowAI.dto.response.PageResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서 서비스 < API 8, API 9, API 10 >
 *
 * 권한 판정은 Backend 개발 명세서 5.2대로 서비스 계층에서 AccessPolicy를 거친다.
 * TODO AccessPolicy가 들어오면 get은 readOrNotFound, update는 writeOrThrow를 이 클래스 안에서 먼저 부른다.
 * TODO create(PermissionService.invite 필요)와 delete(#7)는 이후 추가한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentService {

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;

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

	private Document load(Long documentId) {
		return documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
	}
}
