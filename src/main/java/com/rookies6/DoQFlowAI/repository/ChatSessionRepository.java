package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.ChatSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 대화(chat_session) 리포지토리 < API 25, 26, 27 >
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

	/**
	 * 내 대화만 조회 < API 26, 27 >
	 * 남의 대화는 없는 대화와 똑같이 빈 값이 되어 404가 된다 (DB 설계서 4.4).
	 */
	Optional<ChatSession> findByIdAndUserId(Long id, Long userId);

	/**
	 * 그 사용자의 대화 전체 삭제 (로그인 성공·로그아웃 시, DB 설계서 4.4)
	 * chat_message, message_source는 DB의 ON DELETE CASCADE로 함께 지워진다.
	 * 행을 하나씩 불러와 지우지 않도록 한 번의 DELETE 문으로 실행한다.
	 *
	 * @return 지운 대화 수. 지울 대화가 없어도 오류가 아니다
	 */
	@Modifying(clearAutomatically = true)
	@Query("delete from ChatSession s where s.user.id = :userId")
	int deleteByUserId(@Param("userId") Long userId);
}
