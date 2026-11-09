package com.rookies6.DoQFlowAI.service;

/**
 * 업로드 파일 계열 < API 18, API 23 >
 *
 * 확장자로 정하며 DB에 별도 컬럼은 두지 않는다 (DB 설계서 4.3, mime_type·확장자에서 파생).
 * 계열에 따라 검증·본문 채우기·원문 보기 응답의 kind가 달라진다.
 *
 */
public enum FileKind {
	TEXT,
	DOC
}
