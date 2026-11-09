package com.rookies6.DoQFlowAI.exception;

/**
 * AI Module 호출 실패 502 < BE 8 >
 * 타임아웃, 5xx, 내부키 불일치, 빈 본문을 모두 이것으로 바꾼다.
 *
 * BusinessException을 상속하지만 기존 생성자 두 개를 그대로 유지한다.
 * 호출하는 쪽에 넘기는 message는 서버 로그용이고,
 * 사용자에게는 AI_UNAVAILABLE의 고정 문구만 나간다.
 */
public class AiModuleException extends BusinessException {

	public AiModuleException(String message) {
		super(ErrorCode.AI_UNAVAILABLE, message);
	}

	public AiModuleException(String message, Throwable cause) {
		super(ErrorCode.AI_UNAVAILABLE, message, cause);
	}
}
