package com.rookies6.DoQFlowAI.exception;

/** 초대 대상 조회·관리자 직급 변경 등에서 대상 사용자가 없을 때 */
public class UserNotFoundException extends BusinessException {

	public UserNotFoundException() {
		super(ErrorCode.USER_NOT_FOUND);
	}

	public UserNotFoundException(String detail) {
		super(ErrorCode.USER_NOT_FOUND, detail);
	}
}
