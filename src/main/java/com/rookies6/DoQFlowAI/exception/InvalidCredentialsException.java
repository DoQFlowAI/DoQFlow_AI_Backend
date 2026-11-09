package com.rookies6.DoQFlowAI.exception;

/**
 * 로그인 실패 < API 4.2 >
 * 이메일이 없는 경우와 비밀번호가 틀린 경우에 같은 예외를 쓴다.
 * 문구를 통일해 계정 존재 여부를 노출하지 않는다.
 */
public class InvalidCredentialsException extends BusinessException {

	public InvalidCredentialsException() {
		super(ErrorCode.INVALID_CREDENTIALS);
	}

	public InvalidCredentialsException(String detail) {
		super(ErrorCode.INVALID_CREDENTIALS, detail);
	}
}
