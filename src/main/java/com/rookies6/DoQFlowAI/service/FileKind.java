package com.rookies6.DoQFlowAI.service;

import java.util.Set;

/**
 * 업로드 파일 계열 < API 18, API 23 >
 *
 * 확장자로 정하며 DB에 별도 컬럼은 두지 않는다 (DB 설계서 4.3, mime_type·확장자에서 파생).
 * 계열에 따라 검증·본문 채우기·원문 보기 응답의 kind가 달라진다.
 */
public enum FileKind {
	TEXT,
	DOC;

	// 문서 계열 MIME (UploadFileValidator.FileType의 PDF·DOCX와 같은 값). 나머지 지원 형식은 모두 텍스트 계열이다.
	private static final Set<String> DOC_MIME_TYPES = Set.of(
			"application/pdf",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document");

	/**
	 * 저장된 버전의 mime_type으로 계열을 정한다 < API 23 >
	 * mime_type은 업로드 검증에서 서버가 확장자로 정한 값이라 클라이언트가 바꿀 수 없다.
	 */
	public static FileKind fromMimeType(String mimeType) {
		return DOC_MIME_TYPES.contains(mimeType) ? DOC : TEXT;
	}
}
