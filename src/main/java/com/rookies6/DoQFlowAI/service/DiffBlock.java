package com.rookies6.DoQFlowAI.service;

/**
 * 두 버전 본문 사이의 변경 블록 하나
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
