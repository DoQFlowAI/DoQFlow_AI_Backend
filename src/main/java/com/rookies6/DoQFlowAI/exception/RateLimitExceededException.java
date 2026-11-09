package com.rookies6.DoQFlowAI.exception;

/** 사용자당 분당 질문 20회 초과 429 < API 2.4 > */
public class RateLimitExceededException extends BusinessException {

	public RateLimitExceededException() {
		super(ErrorCode.RATE_LIMIT_EXCEEDED);
	}

	public RateLimitExceededException(String detail) {
		super(ErrorCode.RATE_LIMIT_EXCEEDED, detail);
	}
}
