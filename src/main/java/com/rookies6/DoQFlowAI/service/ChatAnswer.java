package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.ChatSourceResponse;
import java.util.List;

/**
 * 질의응답 처리 결과 < API 27 >
 * 근거 재확인을 통과한 답변만 tokens·sources를 가진다. Controller가 이것을 SSE 이벤트로 바꾼다.
 *
 * @param messageId 저장한 답변 메시지 ID
 * @param noAnswer  근거 없음. 권한 없음·문서 없음·검색 실패·근거 재확인 실패를 구분하지 않는다
 * @param tokens    AI Module이 보낸 순서대로의 토큰
 * @param sources   답변 근거
 */
public record ChatAnswer(
		Long messageId,
		boolean noAnswer,
		List<String> tokens,
		List<ChatSourceResponse> sources
) {

	static ChatAnswer noAnswer(Long messageId) {
		return new ChatAnswer(messageId, true, List.of(), List.of());
	}
}
