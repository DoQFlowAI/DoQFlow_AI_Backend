package com.rookies6.DoQFlowAI.exception;

/**
 * 버전이 없을 때, 그리고 그 버전을 열람할 권한이 없을 때 던진다.
 * code는 일부러 DOCUMENT_NOT_FOUND다. 버전 단위로 다른 코드를 쓰면
 * 문서의 버전 개수를 밖에서 셀 수 있게 된다 < BE 8 >
 */
public class VersionNotFoundException extends BusinessException {

	public VersionNotFoundException() {
		super(ErrorCode.DOCUMENT_NOT_FOUND);
	}

	public VersionNotFoundException(Long versionId) {
		super(ErrorCode.DOCUMENT_NOT_FOUND, "versionId=" + versionId);
	}
}
