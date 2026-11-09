package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 문서 버전 조회 리포지토리
 * 문서 단건 < API 9 >, 변천사 < API 22 >, 두 버전 비교 < API 24 >
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
}
