package com.rookies6.DoQFlowAI.dto.response;

/**
 * 성공 응답 공통 봉투 < API 2.1 >
 * { "success": true, "data": {}, "message": "" }
 *
 * 모든 컨트롤러는 이 타입으로 감싸 반환한다.
 * message는 화면에 그대로 띄울 안내 문구이고, 없으면 빈 문자열로 둔다.
 */
public record ApiResponse<T>(
		boolean success,
		T data,
		String message
) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, "");
	}

	public static <T> ApiResponse<T> ok(T data, String message) {
		return new ApiResponse<>(true, data, message == null ? "" : message);
	}

	/** 돌려줄 데이터가 없는 경우 (로그아웃, 삭제, 권한 회수 등) */
	public static ApiResponse<Void> ok() {
		return new ApiResponse<>(true, null, "");
	}

	public static ApiResponse<Void> ok(String message) {
		return new ApiResponse<>(true, null, message == null ? "" : message);
	}
}
