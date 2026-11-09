package com.rookies6.DoQFlowAI.service;

/**
 * 업로드 파일 계열 < API 18, API 23 >
 *
 * <p>확장자로 정하며 DB에 별도 컬럼은 두지 않는다 (DB 설계서 4.3, mime_type·확장자에서 파생).
 * 계열에 따라 검증·본문 채우기·원문 보기 응답의 kind가 달라진다.
 *
 * <ul>
 *   <li>TEXT — txt md csv json xml yaml yml log. UTF-8 디코딩으로 검증하고,
 *       업로드 트랜잭션 안에서 파일 내용을 그대로 content_text에 넣는다 (AI 추출 호출 없음).</li>
 *   <li>DOC — pdf docx. 매직 넘버로 검증하고, 후처리에서 /internal/extract로 본문을 뽑는다.</li>
 * </ul>
 */
public enum FileKind {
	TEXT,
	DOC
}
