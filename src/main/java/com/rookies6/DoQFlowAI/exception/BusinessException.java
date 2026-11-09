package com.rookies6.DoQFlowAI.exception;

/**
 * 업무 예외의 공통 상위 타입. 도메인 예외는 모두 이 클래스를 상속한다.
 * GlobalExceptionHandler가 errorCode만 보고 상태·코드·문구를 결정하므로
 * 새 예외를 추가할 때 핸들러를 고칠 일이 없다.
 *
 * detail은 서버 로그용이다. 응답 문구로는 쓰지 않는다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.name());
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
		super(detail, cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
