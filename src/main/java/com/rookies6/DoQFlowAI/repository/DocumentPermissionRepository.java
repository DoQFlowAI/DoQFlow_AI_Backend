package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 열람 권한(document_permission) 리포지토리 — 담당 홍서연
 *
 * <p>사용처
 * <ul>
 *   <li>[AccessPolicy] 읽기 판정 — existsAccepted()</li>
 *   <li>[AccessPolicy] 질의응답 scope 계산 — findAccessibleDocumentIds()</li>
 *   <li>[API P-2] 열람자 목록 — findByDocumentIdWithUser()</li>
 *   <li>[API P-3·P-4] 초대 · 권한 회수 — findByDocumentIdAndUserId()</li>
 *   <li>[API P-5~7] 내 초대함 — findByUserIdAndStatus()</li>
 * </ul>
 *
 * <p>열람 가능 판정은 ACCEPTED 행만 인정한다. PENDING·REJECTED는 열람 불가다.
 */
public interface DocumentPermissionRepository extends JpaRepository<DocumentPermission, Long> {

	/** [AccessPolicy] 이 사용자가 이 문서의 초대를 수락했는지 */
	boolean existsByDocumentIdAndUserIdAndStatus(Long documentId, Long userId, PermissionStatus status);

	/** [API P-3·P-4] 초대 중복 확인과 권한 회수. 상태와 무관하게 행을 찾는다 */
	Optional<DocumentPermission> findByDocumentIdAndUserId(Long documentId, Long userId);

	/** [API P-2] 열람자 목록. 이름·직급을 함께 내리므로 user를 fetch join한다 */
	@Query("""
			select p from DocumentPermission p
			  join fetch p.user
			 where p.document.id = :documentId
			 order by p.grantedAt asc
			""")
	List<DocumentPermission> findByDocumentIdWithUser(@Param("documentId") Long documentId);

	/** [API P-5~7] 내 초대함 */
	List<DocumentPermission> findByUserIdAndStatus(Long userId, PermissionStatus status);
	
	/**
	 * [API P-5] 내 초대함. 문서명·초대자명을 함께 내리므로 fetch join 한다.
	 *
	 * <p>document와 grantedBy 모두 LAZY라, 없으면 목록 길이만큼 쿼리가 더 나간다.
	 */
	@Query("""
			select p from DocumentPermission p
			  join fetch p.document
			  join fetch p.grantedBy
			 where p.user.id = :userId
			   and p.status = :status
			 order by p.grantedAt desc
			""")
	List<DocumentPermission> findByUserIdAndStatusWithDocument(
			@Param("userId") Long userId,
			@Param("status") PermissionStatus status);

	/**
	 * [AccessPolicy] 읽을 수 있는 문서 id 전체 — 내가 등록한 것 + 수락한 초대
	 *
	 * <p>질의응답 scope 계산의 1단계다. 목록 API와 같은 조건이어야 하므로
	 * DocumentRepository.findAccessible()의 where 절과 같은 모양을 유지한다.
	 * 한쪽만 바뀌면 "목록에는 없는데 질문에는 잡히는" 유출이 생긴다 < SEC-02 >
	 */
	@Query("""
			select d.id from Document d
			 where d.uploadedBy.id = :userId
			    or exists (select 1 from DocumentPermission p
			                where p.document = d
			                  and p.user.id = :userId
			                  and p.status = com.rookies6.DoQFlowAI.entity.PermissionStatus.ACCEPTED)
			""")
	List<Long> findAccessibleDocumentIds(@Param("userId") Long userId);
}
