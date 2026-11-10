package com.rookies6.DoQFlowAI.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.dto.response.ChatSourceResponse;
import com.rookies6.DoQFlowAI.service.ChatAnswer;
import com.rookies6.DoQFlowAI.service.ChatService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 질의응답 SSE 이벤트 구성 < API 27 >
 * 화면은 token·sources·[DONE]만 처리한다 (Frontend 개발 명세서 SSE 처리). 그 밖의 이벤트만 보내면 화면에 아무것도 나오지 않는다.
 */
class AiControllerEventsTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	@DisplayName("근거 없음은 서버 문구를 token 이벤트로 보내고 끝낸다")
	void noAnswerIsSentAsTokenEvent() {
		List<Object> events = AiController.events(new ChatAnswer(9L, true, List.of(), List.of()));

		assertThat(events).hasSize(2);
		assertThat(JSON.writeValueAsString(events.get(0)))
				.isEqualTo("{\"token\":\"" + ChatService.NO_ANSWER_MESSAGE + "\",\"noAnswer\":true}");
		assertThat(events.get(1)).isEqualTo(AiController.DONE);
	}

	@Test
	@DisplayName("답변은 근거 뒤에 토큰을 순서대로 보내고 끝낸다")
	void answerSendsSourcesThenTokens() {
		ChatSourceResponse source = new ChatSourceResponse(7L, "운영지침", 2, null, "결재는 실장이 승인한다.");
		List<Object> events = AiController.events(
				new ChatAnswer(10L, false, List.of("결재는", " 실장이"), List.of(source)));

		assertThat(events).containsExactly(
				Map.of("sources", List.of(source)),
				Map.of("token", "결재는"),
				Map.of("token", " 실장이"),
				AiController.DONE);
	}
}
