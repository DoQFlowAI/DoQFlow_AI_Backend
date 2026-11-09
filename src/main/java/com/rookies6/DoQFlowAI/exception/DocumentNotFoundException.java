package com.rookies6.DoQFlowAI.exception;

/**
 * 문서가 없을 때, 그리고 열람 권한이 없을 때 양쪽에 던진다.
 * 둘을 구분해 응답하면 응답만으로 문서의 존재가 드러난다 < SEC-03 >
 * 권한 없음으로 막을 때는 AccessPolicy가 이 예외를 던진다.
 */
public class DocumentNotFoundException extends BusinessException {

	public DocumentNotFoundException() {
		super(ErrorCode.DOCUMENT_NOT_FOUND);
	}

	/** detail은 로그에만 남는다. documentId를 넣어도 응답에는 나가지 않는다 */
	public DocumentNotFoundException(Long documentId) {
		super(ErrorCode.DOCUMENT_NOT_FOUND, "documentId=" + documentId);
	}
}
