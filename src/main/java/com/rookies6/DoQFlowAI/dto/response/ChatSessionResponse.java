package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.ChatSession;

/**
 * 대화 생성 응답 (POST, /chat/sessions) < API 25 >
 *
 * <pre>
 * { "sessionId": 12, "title": null }
 * </pre>
 *
 * @param title 대화 제목. 생성 직후에는 null이고 첫 질문 앞부분으로 채워진다
 */
public record ChatSessionResponse(
		Long sessionId,
		String title
) {

	public static ChatSessionResponse from(ChatSession session) {
		return new ChatSessionResponse(session.getId(), session.getTitle());
	}
}
