package com.rookies6.DoQFlowAI.security;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import com.rookies6.DoQFlowAI.entity.RankLevel;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.exception.ForbiddenException;
import com.rookies6.DoQFlowAI.exception.UserNotFoundException;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import com.rookies6.DoQFlowAI.repository.DocumentPermissionRepository;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 권한 판정의 단일 진입점 < SEC-02 >
 *
 * <p>문서 목록·단건·버전·원문·이력·비교·질의응답·대화 기록이 전부 이 클래스를 호출한다.
 * 경로마다 따로 구현하면 빠뜨린 경로가 그대로 우회 통로가 되기 때문이다.
 *
 * <p><b>권한 세 갈래</b> (04 §5)
 * <ul>
 *   <li>읽기 — 그 문서의 등록자이거나, document_permission에 ACCEPTED 행이 있음</li>
 *   <li>등록 — 직급이 팀장(2)</li>
 *   <li>쓰기 — 그 문서의 등록자 본인 (버전 추가·삭제, 초대 관리)</li>
 * </ul>
 *
 * <p><b>직급은 사원(1)·팀장(2) 2단계이고 role 컬럼은 없다.</b>
 * 설계서 04·05에 남아 있는 "부장(4) 또는 ADMIN" 표기는 머지된 V1 스키마와 맞지 않는다.
 * 이 클래스는 스키마를 기준으로 하며 ADMIN 분기를 두지 않는다. 설계서 수정은 이슈 J.
 *
 * <p><b>404와 403을 섞지 않는다.</b> 못 읽는 문서는 404, 읽을 수는 있으나 등록자가 아니면 403이다.
 * 못 읽는 문서에 403을 쓰면 응답 코드만으로 문서의 존재가 드러난다 < SEC-03 >
 *
 * <p><b>호출하는 쪽 주의 — 트랜잭션</b>
 * 이 클래스는 {@code @Transactional(readOnly = true)}다. 호출하는 서비스에 트랜잭션이 있으면
 * 그 트랜잭션에 참여하므로 readOnly는 무시되고 아무 문제가 없다.
 * 그런데 <b>컨트롤러에서 바로 호출하면</b> 여기서 읽기 전용 트랜잭션이 열리고 닫혀서,
 * 돌려받은 엔티티가 준영속 상태가 된다. 그 엔티티를 고쳐도 DB에 반영되지 않는다.
 * 돌려받은 엔티티를 수정할 거면 <b>반드시 서비스의 {@code @Transactional} 안에서 호출</b>한다.
 * 읽기만 할 때는 컨트롤러에서 바로 호출해도 된다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccessPolicy {

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;
	private final DocumentPermissionRepository permissions;
	private final AppUserRepository users;

	// ------------------------------------------------------------------ 읽기

	/**
	 * 읽을 수 있으면 문서를 돌려주고, 아니면 404로 막는다.
	 *
	 * <p>문서가 없는 경우와 권한이 없는 경우에 <b>같은 예외</b>를 던진다.
	 * 둘을 구분하면 응답만으로 문서의 존재를 알 수 있다.
	 *
	 * @throws DocumentNotFoundException 문서가 없거나 읽을 권한이 없을 때 (404)
	 */
	public Document readOrNotFound(Long userId, Long documentId) {
		Document document = documents.findById(documentId)
				.orElseThrow(() -> new DocumentNotFoundException(documentId));

		if (isOwner(document, userId)) {
			return document;
		}
		boolean invited = permissions.existsByDocumentIdAndUserIdAndStatus(
				documentId, userId, PermissionStatus.ACCEPTED);
		if (invited) {
			return document;
		}
		throw new DocumentNotFoundException(documentId);
	}

	/** 목록 응답의 owned 플래그용. 화면은 이 값으로 업로드·초대 버튼 노출을 정한다 (04 §5.4) */
	public boolean isOwner(Document document, Long userId) {
		// 프록시의 getId()는 초기화를 유발하지 않으므로 추가 조회가 없다
		return document.getUploadedBy().getId().equals(userId);
	}

	// ------------------------------------------------------------------ 쓰기

	/**
	 * 그 문서에 쓸 수 있으면 문서를 돌려준다. 버전 추가·삭제, 문서 수정·삭제, 초대 관리에 쓴다.
	 *
	 * <p><b>판정 순서가 중요하다.</b> 먼저 readOrNotFound로 404를 판정하고,
	 * 그 다음 등록자 여부로 403을 판정한다. 순서를 바꿔 등록자 검사를 먼저 하면
	 * 읽을 수도 없는 문서에 403이 나가면서 문서의 존재가 드러난다.
	 *
	 * @throws DocumentNotFoundException 읽을 수 없을 때 (404)
	 * @throws ForbiddenException        읽을 수는 있으나 등록자가 아닐 때 (403)
	 */
	public Document writeOrThrow(Long userId, Long documentId) {
		Document document = readOrNotFound(userId, documentId);
		if (!isOwner(document, userId)) {
			throw new ForbiddenException("not the uploader: documentId=" + documentId);
		}
		return document;
	}

	// ------------------------------------------------------------------ 등록

	/**
	 * 문서를 등록할 수 있는 직급인지 판정한다. 팀장(2)만 가능하다.
	 *
	 * <p>직급은 <b>토큰이 아니라 DB에서</b> 읽는다. 토큰에 직급을 넣으면
	 * 직급이 바뀐 뒤에도 기존 토큰이 만료될 때까지 예전 권한으로 통과한다 < R-2 >
	 *
	 * @throws ForbiddenException 사원(1)일 때 (403)
	 */
	public void registerOrThrow(Long userId) {
		AppUser user = users.findById(userId)
				.orElseThrow(() -> new UserNotFoundException("userId=" + userId));
		if (user.getRankLevel() != RankLevel.LEAD.level()) {
			throw new ForbiddenException("rankLevel=" + user.getRankLevel() + " cannot register");
		}
	}

	/** 화면 분기용. 막는 목적이면 registerOrThrow를 쓴다 */
	public boolean canRegister(Long userId) {
		return users.findById(userId)
				.map(u -> u.getRankLevel() == RankLevel.LEAD.level())
				.orElse(false);
	}

	// ------------------------------------------------------------------ 버전

	/**
	 * 버전을 읽을 수 있으면 돌려준다. 판정은 그 버전이 속한 문서의 읽기 권한을 그대로 따른다.
	 *
	 * <p>문서를 읽을 수 있으면 그 안의 버전·원문·타임라인·비교가 전부 열리고,
	 * 못 읽으면 전부 404다. 버전 단위로 다른 코드를 쓰면 버전 개수를 밖에서 셀 수 있다.
	 *
	 * @throws VersionNotFoundException  버전이 없을 때 (404, code는 DOCUMENT_NOT_FOUND)
	 * @throws DocumentNotFoundException 그 문서를 읽을 수 없을 때 (404)
	 */
	public DocumentVersion readVersionOrNotFound(Long userId, Long versionId) {
		DocumentVersion version = versions.findById(versionId)
				.orElseThrow(() -> new VersionNotFoundException(versionId));
		readOrNotFound(userId, version.getDocument().getId());
		return version;
	}

	/**
	 * 버전을 삭제할 수 있으면 돌려준다. 그 문서의 등록자만 가능하다.
	 *
	 * @throws VersionNotFoundException  버전이 없을 때 (404)
	 * @throws DocumentNotFoundException 그 문서를 읽을 수 없을 때 (404)
	 * @throws ForbiddenException        읽을 수는 있으나 등록자가 아닐 때 (403)
	 */
	public DocumentVersion writeVersionOrThrow(Long userId, Long versionId) {
		DocumentVersion version = versions.findById(versionId)
				.orElseThrow(() -> new VersionNotFoundException(versionId));
		writeOrThrow(userId, version.getDocument().getId());
		return version;
	}

	// ------------------------------------------------------------- 질의응답 scope

	/**
	 * 질의응답이 검색할 수 있는 버전 id 목록 < Q-1 >
	 *
	 * <p>읽을 수 있는 문서의 최신 버전 1건씩만 담는다. 최신 1건으로 제한해
	 * 과거 버전의 내용이 답변 근거로 섞이는 것을 막는다.
	 *
	 * <p>명세 기준은 "각 문서의 currentVersionNo에 해당하는 버전 ID"다 (05 §254).
	 * 업로드 시 VersionService가 currentVersionNo를 먼저 올리므로
	 * 지금 쿼리의 max(versionNo)와 결과가 같다.
	 *
	 * <p>쿼리는 DocumentVersionRepository.findLatestIdsByDocumentIds(손인선 소유)를 쓴다.
	 * 그 쿼리가 (documentIds에 속하거나 OR 내가 등록한 문서)로 판정하므로
	 * 등록한 문서는 documentIds에 없어도 포함된다. 빈 목록을 넘겨도 안전하다.
	 *
	 * <p><b>명세 불일치 — 문서 상태 필터가 없다.</b> 명세는 색인이 끝나지 않은 문서를
	 * 검색 대상에서 제외한다 (04 §7 D-3 "READY 전에는 검색되지 않는다",
	 * DocumentStatus 주석 "READY 만 검색 대상"). 그런데 쿼리에 document.status 조건이 없어
	 * PROCESSING·FAILED 문서의 버전도 scope에 들어간다.
	 *
	 * <p>유출도 오답도 아니다. 그 버전은 FAISS에 없으므로 근거 없음으로 끝난다.
	 * 다만 AI Module을 쓸모없이 한 번 호출하고, "왜 답을 못 찾나" 디버깅이 어려워진다.
	 * 쿼리 소유가 손인선이라 이 커밋에서 고치지 않았다. Day 4 질의응답 전에 정리한다.
	 *
	 * <p>Backend는 AI Module이 돌려준 sourceVersionIds를 이 목록과 <b>다시 대조</b>해야 한다.
	 * 목록 밖이 하나라도 있으면 답변 전체를 폐기하고 감사 로그만 남긴다.
	 */
	public List<Long> accessibleVersionIds(Long userId) {
		List<Long> invitedDocumentIds = permissions.findAccessibleDocumentIds(userId);
		return versions.findLatestIdsByDocumentIds(invitedDocumentIds, userId);
	}
}
