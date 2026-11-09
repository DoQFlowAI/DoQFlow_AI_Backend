package com.rookies6.DoQFlowAI.exception;


/** 버전 업로드 파일 검증 < API 18 >
 * GlobalExceptionHandler에서 status와 code로 응답 문구 생성
 * reason은 서버 로그용, 사용자 응답 문구는 ErrorCode의 고정 문구로 사용
 */
public class UploadValidationException extends RuntimeException {

	/** 실패한 검증 단계와 응답 상태
	 * 순서대로
	 * 파일 개수가 1~10개가 아님
	 * 빈 파일
	 * 확장자가 PDF·DOCX가 아니거나 매직 넘버가 맞지 않음
	 * 파일당 20MB 초과
	 * 파일명에 경로 구분자, 절대 경로, 널 바이트가 있음
	 * 직전 버전과 내용 해시가 같음
	 */
	public enum Reason {
		FILE_COUNT(400, "VALIDATION_ERROR"),
		EMPTY_FILE(400, "VALIDATION_ERROR"),
		UNSUPPORTED_TYPE(400, "VALIDATION_ERROR"),
		FILE_TOO_LARGE(413, "FILE_TOO_LARGE"),
		INVALID_FILENAME(400, "VALIDATION_ERROR"),
		SAME_AS_PREVIOUS(400, "VALIDATION_ERROR");

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
