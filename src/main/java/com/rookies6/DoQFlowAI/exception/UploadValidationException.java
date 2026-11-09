package com.rookies6.DoQFlowAI.exception;


/** 버전 업로드 파일 검증 < API 18 >
 * GlobalExceptionHandler에서 status와 code로 응답 문구 생성
 * reason은 서버 로그와 VERSION_UPLOAD_FAILED 감사 로그 detail용, 사용자 응답 문구는 ErrorCode의 고정 문구로 사용
 * reason 이름은 DB 설계서 3.3 VERSION_UPLOAD_FAILED 표와 같다.
 */
public class UploadValidationException extends RuntimeException {

	/** 실패한 검증 단계와 응답 상태 (Backend 개발 명세서 7 검증 순서)
	 * FILE_COUNT        파일이 0개, 11개 이상, 빈 파일
	 * INVALID_TYPE      지원하지 않는 확장자 / 문서 계열 매직 넘버 불일치 / 텍스트 계열 UTF-8 디코딩 실패·널 바이트·제어문자
	 * FILE_TOO_LARGE    파일당 20MB 초과
	 * INVALID_FILENAME  파일명에 경로 구분자, 절대 경로, 널 바이트가 있음
	 * DUPLICATE_CONTENT 직전 버전과 내용 해시가 같음
	 */
	public enum Reason {
		FILE_COUNT(400, "VALIDATION_ERROR"),
		INVALID_TYPE(400, "VALIDATION_ERROR"),
		FILE_TOO_LARGE(413, "FILE_TOO_LARGE"),
		INVALID_FILENAME(400, "VALIDATION_ERROR"),
		DUPLICATE_CONTENT(400, "VALIDATION_ERROR");

		private final int status;
		private final String code;

		Reason(int status, String code) {
			this.status = status;
			this.code = code;
		}

		public int status() {
			return status;
		}

		public String code() {
			return code;
		}
	}

	private final Reason reason;

	public UploadValidationException(Reason reason) {
		super(reason.name());
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}
}
