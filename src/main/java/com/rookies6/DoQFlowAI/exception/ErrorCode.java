package com.rookies6.DoQFlowAI.exception;

/**
 * 오류 코드 표 < API 2.4 · BE 8 >
 * 사용자에게 내려가는 문구는 이 enum의 message로 고정한다.
 * 예외의 생성자에 넘긴 detail은 서버 로그에만 남기고 응답에 싣지 않는다.
 *
 * DOCUMENT_NOT_FOUND는 "없는 리소스"와 "권한 밖 리소스" 양쪽에 같이 쓴다.
 * 응답만으로 문서·버전의 존재를 알 수 없어야 하기 때문이다 < SEC-03 >
 */
public enum ErrorCode {

	VALIDATION_ERROR(400, "입력값을 확인해 주세요."),
	UNAUTHORIZED(401, "인증이 필요합니다."),
	INVALID_CREDENTIALS(401, "이메일 또는 비밀번호가 올바르지 않습니다."),
	FORBIDDEN(403, "권한이 없습니다."),
	DOCUMENT_NOT_FOUND(404, "문서를 찾을 수 없습니다."),
	USER_NOT_FOUND(404, "사용자를 찾을 수 없습니다."),
	NOT_FOUND(404, "요청한 경로를 찾을 수 없습니다."),
	EMAIL_DUPLICATED(409, "이미 가입된 이메일입니다."),
	FILE_TOO_LARGE(413, "파일 크기가 허용 범위를 넘습니다."),
	ACCOUNT_LOCKED(423, "로그인 시도가 많아 계정이 잠겼습니다. 잠시 후 다시 시도해 주세요."),
	RATE_LIMIT_EXCEEDED(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
	INTERNAL_ERROR(500, "요청을 처리할 수 없습니다."),
	AI_UNAVAILABLE(502, "일시적으로 답변할 수 없습니다.");

	private final int status;
	private final String message;

	ErrorCode(int status, String message) {
		this.status = status;
		this.message = message;
	}

	public int status() {
		return status;
	}

	public String message() {
		return message;
	}

	/** 코드 문자열로 역조회. 모르는 값이면 INTERNAL_ERROR로 떨어뜨린다 */
	public static ErrorCode of(String code) {
		if (code != null) {
			for (ErrorCode e : values()) {
				if (e.name().equals(code)) {
					return e;
				}
			}
		}
		return INTERNAL_ERROR;
	}
}
