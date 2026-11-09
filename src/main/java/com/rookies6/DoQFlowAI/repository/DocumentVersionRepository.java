package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 문서 버전 조회 리포지토리
 * 문서 단건
 */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

	// 문서의 버전 목록을 버전 번호 오름차순으로 조회
	List<DocumentVersion> findByDocumentIdOrderByVersionNo(Long documentId);
}
