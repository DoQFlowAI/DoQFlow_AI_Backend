package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 질의응답 요청 (POST, /ai/chat) [ sessionId, question ] < API 27 >
 *
 * 질문이 비었거나 1000자를 넘으면 400 (VALIDATION_ERROR). 사용자 ID는 받지 않는다.
 * 1000자 상한은 ChatService가 app.chat.max-question-length로 한 번 더 확인한다.
 */
public record ChatRequest(

		@NotNull
		Long sessionId,

		@NotBlank
		@Size(max = 1000)
		String question
) {
}
