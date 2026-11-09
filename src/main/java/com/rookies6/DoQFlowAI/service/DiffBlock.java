package com.rookies6.DoQFlowAI.service;

/**
 * 두 버전 본문 사이의 변경 블록 하나 (Backend 개발 명세서 7)
 *
 * <p>사용 API
 * <ul>
 *   <li>[API-18 V-01] 버전 일괄 업로드 — 후처리에서 인접 구간 diff를 계산해 document_version.diff_json에 저장</li>
 *   <li>[API-22 V-05] 변천사 타임라인 — 저장된 diff_json을 읽음 (이슈 #6)</li>
 *   <li>[API-23 C-01] 두 버전 비교 — 비인접 구간은 요청 시 계산 (이슈 #6)</li>
 * </ul>
 *
 * <p>java-diff-utils의 AbstractDelta를 그대로 옮긴 값이며, 변경 없는 EQUAL 블록은 만들지 않는다.
 * 줄 위치(fromLine, toLine)는 0부터 센다.
 *
 * @param type      INSERT / DELETE / CHANGE
 * @param fromLine  이전 버전에서 블록이 시작하는 줄 (delta.getSource().getPosition())
 * @param fromCount 이전 버전에서 블록이 차지하는 줄 수 (delta.getSource().size())
 * @param toLine    다음 버전에서 블록이 시작하는 줄 (delta.getTarget().getPosition())
 * @param toCount   다음 버전에서 블록이 차지하는 줄 수 (delta.getTarget().size())
 */
public record DiffBlock(
		String type,
		int fromLine,
		int fromCount,
		int toLine,
		int toCount
) {
}
