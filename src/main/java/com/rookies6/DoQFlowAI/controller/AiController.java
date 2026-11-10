package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.request.ChatRequest;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.security.RateLimiter;
import com.rookies6.DoQFlowAI.service.ChatAnswer;
import com.rookies6.DoQFlowAI.service.ChatService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 질의응답 API ( POST /ai/chat ) < API 27 > -  응답 text/event-stream
 *
 * 답변은 근거 재확인을 통과한 뒤에만 보낸다. ChatService가 끝까지 처리한 뒤 이벤트를 한 번에 내보낸다.
 * 검증 실패(400), 남의 대화(404), 분당 20회 초과(429), AI Module 실패(502)는 일반 오류 응답(JSON)으로 나간다.
 * 횟수 제한은 가장 먼저 확인한다. 한도를 넘은 요청은 DB·AI Module(LLM)까지 가지 않는다.
 *
 * 이벤트 순서
 *   답변     data: {"sources": [...]} → data: {"token": "..."} 여러 번 → data: [DONE]
 *   근거 없음 data: {"token": "문서에서 답을 찾지 못했습니다.", "noAnswer": true} → data: [DONE]
 *
 * 근거 없음 문구를 token 이벤트로 보내는 이유: 화면은 token·sources·[DONE]만 처리하고 근거 없음은 서버 문구를 그대로
 * 표시한다 (Frontend 개발 명세서 4장·SSE 처리). noAnswer는 화면이 구분하고 싶을 때 쓰도록 같이 넣는다.
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AiController {

	static final String DONE = "[DONE]";

	private final ChatService chatService;
	private final RateLimiter rateLimiter;

	@PostMapping("/chat")
	public SseEmitter chat(@CurrentUser AppUser me, @Valid @RequestBody ChatRequest request) throws IOException {
		rateLimiter.checkChat(me.getId());
		ChatAnswer answer = chatService.ask(me, request.sessionId(), request.question());

		// 보낼 내용이 이미 정해져 있으므로 반환 전에 모두 보내고 닫는다. 반환 전에 보낸 이벤트는 SseEmitter가 모아 두었다가 쓴다
		SseEmitter emitter = new SseEmitter();
		for (Object event : events(answer)) {
			if (event instanceof String text) {
				emitter.send(SseEmitter.event().data(text, MediaType.TEXT_PLAIN));
			} else {
				emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
			}
		}
		emitter.complete();
		return emitter;
	}

	static List<Object> events(ChatAnswer answer) {
		if (answer.noAnswer()) {
			Map<String, Object> noAnswer = new java.util.LinkedHashMap<>();
			noAnswer.put("token", ChatService.NO_ANSWER_MESSAGE);
			noAnswer.put("noAnswer", true);
			return List.of(noAnswer, DONE);
		}
		List<Object> events = new java.util.ArrayList<>();
		events.add(Map.of("sources", answer.sources()));
		for (String token : answer.tokens()) {
			events.add(Map.of("token", token));
		}
		events.add(DONE);
		return events;
	}
}
