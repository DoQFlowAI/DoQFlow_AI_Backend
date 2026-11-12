package com.rookies6.DoQFlowAI.exception;

/**
 * 대화가 없거나 남의 대화일 때 404 < API 26, 27 >
 * API 설계서 6.2의 오류 표대로 DOCUMENT_NOT_FOUND를 쓴다. 남의 대화와 없는 대화를 구분하지 않는다.
 */
public class ChatSessionNotFoundException extends BusinessException {

	public ChatSessionNotFoundException(Long sessionId) {
		super(ErrorCode.DOCUMENT_NOT_FOUND, "chatSessionId=" + sessionId);
	}
}
