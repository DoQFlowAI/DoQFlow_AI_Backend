package com.rookies6.DoQFlowAI.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rookies6.DoQFlowAI.entity.ChatMessage;
import com.rookies6.DoQFlowAI.entity.MessageRole;
import java.time.Instant;
import java.util.List;

/**
 * 대화 기록 항목 (GET, /chat/sessions/{sessionId}/messages) < API 26 >
 *
 * <pre>
 * { "messageId": 31, "role": "USER", "content": "...", "createdAt": "2026-11-06T09:30:00Z" }
 * { "messageId": 32, "role": "ASSISTANT", "content": "...", "createdAt": "...", "masked": false,
 *   "sources": [ { "documentId": 7, "documentName": "...", "versionNo": 4, "pageNumber": 12, "chunkText": "..." } ] }
 * </pre>
 *
 * 질문(USER)에는 masked, sources를 넣지 않는다.
 * 가려진 답변은 masked = true, content = "", sources = [] 다 (DB 설계서 4.6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessageResponse(
		Long messageId,
		MessageRole role,
		String content,
		Instant createdAt,
		Boolean masked,
		List<ChatSourceResponse> sources
) {

	public static ChatMessageResponse question(ChatMessage message) {
		return new ChatMessageResponse(message.getId(), message.getRole(), message.getContent(),
				ApiTime.toInstant(message.getCreatedAt()), null, null);
	}

	public static ChatMessageResponse answer(ChatMessage message, List<ChatSourceResponse> sources) {
		return new ChatMessageResponse(message.getId(), message.getRole(), message.getContent(),
				ApiTime.toInstant(message.getCreatedAt()), false, sources);
	}

	public static ChatMessageResponse maskedAnswer(ChatMessage message) {
		return new ChatMessageResponse(message.getId(), message.getRole(), "",
				ApiTime.toInstant(message.getCreatedAt()), true, List.of());
	}
}
