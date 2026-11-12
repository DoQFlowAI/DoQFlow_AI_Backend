package com.rookies6.DoQFlowAI.exception;

/**
 * 지금 상태에서는 지울 수 없음 — 400 VALIDATION_ERROR < API 11, API 21 >
 *
 * 버전이 하나뿐인 문서의 버전 삭제
 * 업로드 후처리 중(document.status = PROCESSING)인 문서·버전 삭제. 후처리가 지운 행을 다시 저장하려다 실패하는 것을 막는다.
 */
public class DeleteNotAllowedException extends BusinessException {

	public DeleteNotAllowedException(String detail) {
		super(ErrorCode.VALIDATION_ERROR, detail);
	}
}
