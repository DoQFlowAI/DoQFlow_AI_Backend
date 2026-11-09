package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.MessageSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 답변 근거(message_source) 리포지토리 < API 26, 27 >
 */
public interface MessageSourceRepository extends JpaRepository<MessageSource, Long> {

	/**
	 * 여러 답변의 근거를 한 번에 조회 < API 26 >
	 * 가림 판정에 근거 버전의 문서가 필요하므로 버전과 문서를 같이 불러와 N+1 조회를 막는다.
	 * 버전이 삭제된 근거(version_id NULL)도 빠지지 않도록 left join으로 가져온다.
	 */
	@Query("""
			select s from MessageSource s
			  left join fetch s.version v
			  left join fetch v.document
			 where s.message.id in :messageIds
			 order by s.id
			""")
	List<MessageSource> findByMessageIdIn(@Param("messageIds") Collection<Long> messageIds);
}
