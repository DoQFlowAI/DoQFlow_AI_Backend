package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.exception.ErrorCode;

/**
 * 오류 응답 공통 봉투 < API 2.2 · BE 8 >
 * { "success": false, "error": { "code": "", "message": "", "traceId": "" } }
 *
 * message는 ErrorCode의 고정 문구만 쓴다. 예외 메시지를 그대로 싣지 않는다.
 * 스택 트레이스·SQL·파일 경로는 traceId로 서버 로그에서만 확인한다.
 */
public record ErrorResponse(
		boolean success,
		Detail error
) {

	public record Detail(
			String code,
			String message,
			String traceId
	) {
	}

	public static ErrorResponse of(ErrorCode errorCode, String traceId) {
		return new ErrorResponse(
				false,
				new Detail(errorCode.name(), errorCode.message(), traceId == null ? "" : traceId));
	}

	/**
	 * 입력값 검증 실패처럼 어느 필드가 문제인지 알려줘도 되는 경우에만 문구를 바꿔 쓴다.
	 * 권한·인증 관련 오류에는 쓰지 않는다.
	 */
	public static ErrorResponse of(ErrorCode errorCode, String message, String traceId) {
		return new ErrorResponse(
				false,
				new Detail(errorCode.name(), message, traceId == null ? "" : traceId));
	}
}
