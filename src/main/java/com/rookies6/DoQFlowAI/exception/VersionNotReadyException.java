package com.rookies6.DoQFlowAI.exception;

/**
 * 비교할 수 없는 상태의 버전 — 400 VALIDATION_ERROR < API 24 >
 *
 * document_version.status가 FAILED인 버전은 비교 대상에서 제외한다.
 * 버전이 있다는 사실은 읽기 권한자에게만 드러난다 (권한 확인을 먼저 하므로).
 */
public class VersionNotReadyException extends BusinessException {

	public VersionNotReadyException() {
		super(ErrorCode.VALIDATION_ERROR, "version is not ready for compare");
	}
}
