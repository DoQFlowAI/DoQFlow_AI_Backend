package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 문서(document) 조회 리포지토리
 *
 * <p>사용 API (이슈 #2)
 * <ul>
 *   <li>[API-07 D-1] 문서 생성 — 기본 save()</li>
 *   <li>[API-08 D-2] 문서 목록 — findAccessible()</li>
 *   <li>[API-09 D-3] 문서 단건 — AccessPolicy.readOrNotFound()가 기본 findById() 사용</li>
 *   <li>[API-10 D-4] 문서 수정 — AccessPolicy.writeOrThrow()가 기본 findById() 사용</li>
 * </ul>
 *
 * <p>권한 판정(읽기/쓰기)은 AccessPolicy가 단일 진입점이므로 여기서는 조회 조건만 제공한다.
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

	/**
	 * [API-08 D-2] 문서 목록 — 읽을 수 있는 문서만 페이징 조회
	 *
	 * <p>읽기 조건: 본인이 등록한 문서이거나, 초대를 수락(ACCEPTED)한 문서.
	 * PENDING·REJECTED 초대 문서는 목록에서 제외된다.
	 *
	 * <p>목록 응답에 등록자 이름·직급이 들어가므로 uploadedBy를 fetch join해 N+1 조회를 막는다.
	 * fetch join은 count 쿼리에 쓸 수 없으므로 countQuery를 따로 둔다.
	 * totalElements는 이 권한 필터를 적용한 뒤의 개수다 (API 설계서 2.3).
	 */
	@Query(value = """
			select d from Document d
			  join fetch d.uploadedBy
			 where d.uploadedBy.id = :userId
			    or exists (select 1 from DocumentPermission p
			                where p.document = d
			                  and p.user.id = :userId
			                  and p.status = com.rookies6.DoQFlowAI.entity.PermissionStatus.ACCEPTED)
			""",
			countQuery = """
			select count(d) from Document d
			 where d.uploadedBy.id = :userId
			    or exists (select 1 from DocumentPermission p
			                where p.document = d
			                  and p.user.id = :userId
			                  and p.status = com.rookies6.DoQFlowAI.entity.PermissionStatus.ACCEPTED)
			""")
	Page<Document> findAccessible(@Param("userId") Long userId, Pageable pageable);

	/**
	 * 기동 시 복구 — 끊긴 후처리로 PROCESSING에 남은 문서를 FAILED로 (ProcessingRecovery). 바꾼 행 수
	 * updated_at도 함께 바꾼다. 일괄 UPDATE는 엔티티의 @PreUpdate를 거치지 않는다.
	 * 시각은 앱이 넘긴다 (@PreUpdate와 같은 LocalDateTime.now(). DB 시간대가 달라도 어긋나지 않게).
	 */
	@Modifying(clearAutomatically = true)
	@Query("""
			update Document d set d.status = com.rookies6.DoQFlowAI.entity.DocumentStatus.FAILED,
			       d.updatedAt = :now
			 where d.status = com.rookies6.DoQFlowAI.entity.DocumentStatus.PROCESSING
			""")
	int failAllProcessing(@Param("now") java.time.LocalDateTime now);
}
