package com.rookies6.DoQFlowAI.exception;

/** 가입 시 이메일 중복 409 < API 4.1 > */
public class EmailDuplicatedException extends BusinessException {

	public EmailDuplicatedException() {
		super(ErrorCode.EMAIL_DUPLICATED);
	}

	public EmailDuplicatedException(String detail) {
		super(ErrorCode.EMAIL_DUPLICATED, detail);
	}
}
