package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.ChatMessageResponse;
import com.rookies6.DoQFlowAI.dto.response.ChatSessionResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.service.ChatService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대화 API < API 25, API 26 > (API 설계서 6.1)
 */
@RestController
@RequestMapping("/chat/sessions")
@RequiredArgsConstructor
public class ChatController {

	private final ChatService chatService;

	/** 대화 생성 < API 25 > — 사용자 ID는 받지 않는다. 201 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<ChatSessionResponse> create(@CurrentUser AppUser me) {
		return ApiResponse.ok(chatService.createSession(me));
	}

	/**
	 * 대화 기록 < API 26 > — 내 대화만. 남의 대화는 404
	 * 지금 읽을 수 없는 문서를 근거로 한 답변은 masked = true로 내용과 근거를 비운다.
	 */
	@GetMapping("/{sessionId}/messages")
	public ApiResponse<List<ChatMessageResponse>> messages(@CurrentUser AppUser me, @PathVariable Long sessionId) {
		return ApiResponse.ok(chatService.listMessages(me, sessionId));
	}
}
