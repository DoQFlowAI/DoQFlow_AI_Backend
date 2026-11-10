package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 질문·답변(chat_message) 리포지토리 < API 26, 27 >
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	/**
	 * 대화의 메시지를 쓴 순서대로 조회 < API 26 >
	 * 질문과 답변이 같은 초에 저장될 수 있어 id로 한 번 더 정렬한다.
	 */
	List<ChatMessage> findBySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);
}
