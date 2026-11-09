package com.rookies6.DoQFlowAI.exception;

/** 연속 5회 로그인 실패로 15분 잠긴 계정 < API 4.2 > */
public class AccountLockedException extends BusinessException {

	public AccountLockedException() {
		super(ErrorCode.ACCOUNT_LOCKED);
	}

	public AccountLockedException(String detail) {
		super(ErrorCode.ACCOUNT_LOCKED, detail);
	}
}
