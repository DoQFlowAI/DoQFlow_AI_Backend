package com.rookies6.DoQFlowAI.exception;

/**
 * 볼 수는 있으나 그 동작을 할 수 없을 때. 직급 부족, 업로더가 아닌 리소스의 삭제 등.
 * 열람 자체가 안 되는 경우에는 이 예외가 아니라 DocumentNotFoundException을 쓴다.
 */
public class ForbiddenException extends BusinessException {

	public ForbiddenException() {
		super(ErrorCode.FORBIDDEN);
	}

	public ForbiddenException(String detail) {
		super(ErrorCode.FORBIDDEN, detail);
	}
}
