package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 문서 버전 조회 리포지토리
 * 문서 단건 < API 9 >, 버전 업로드 < API 18 >, 변천사 < API 22 >, 두 버전 비교 < API 24 >
 */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

	// 문서의 버전 목록을 버전 번호 오름차순으로 조회 < API 9, API 22 >
	List<DocumentVersion> findByDocumentIdOrderByVersionNo(Long documentId);

	// 문서의 특정 버전 조회 < API 24 > (API 20 버전 단건에서도 재사용 예정)
	Optional<DocumentVersion> findByDocumentIdAndVersionNo(Long documentId, int versionNo);

	/**
	 * 지정한 버전 바로 앞에 남아 있는 버전 번호 < API 24 >
	 * 버전이 삭제되어 번호가 비어도 실제 직전 버전을 찾는다. 앞 버전이 없으면 null.
	 * 본문(LONGTEXT)을 불러오지 않도록 번호만 조회한다.
	 */
	@Query("""
			select max(v.versionNo) from DocumentVersion v
			 where v.document.id = :documentId
			   and v.versionNo < :versionNo
			""")
	Integer findPreviousVersionNo(@Param("documentId") Long documentId, @Param("versionNo") int versionNo);

	/**
	 * 문서의 가장 큰 버전 번호 < API 18 >
	 * 새 버전은 이 값 + 1부터 이어서 붙인다. 버전이 없으면 0.
	 */
	@Query("""
			select coalesce(max(v.versionNo), 0) from DocumentVersion v
			 where v.document.id = :documentId
			""")
	int findMaxVersionNo(@Param("documentId") Long documentId);

	/**
	 * 문서의 마지막 버전 < API 18 >
	 * 추가 업로드 시 직전 버전 해시 비교(같으면 400)와, 직전 버전 → 새 첫 버전 구간 diff에 쓴다.
	 */
	Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNoDesc(Long documentId);

	/**
	 * 질의응답 scope — 읽기 가능한 문서의 최신 버전 ID (Backend 개발 명세서 4.4, AccessPolicy 전용)
	 * 내가 등록한 문서 + 초대를 수락한 문서(documentIds)의 마지막 버전만 모은다.
	 * documentIds가 비어 있어도 내가 등록한 문서는 포함된다.
	 */
	@Query("""
			select v.id from DocumentVersion v
			 where (v.document.id in :documentIds or v.document.uploadedBy.id = :userId)
			   and v.versionNo = (select max(v2.versionNo) from DocumentVersion v2
			                       where v2.document = v.document)
			""")
	List<Long> findLatestIdsByDocumentIds(@Param("documentIds") Collection<Long> documentIds,
			@Param("userId") Long userId);
}
