package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.ChatSessionResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대화 API < API 25 > (API 설계서 6.1)
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
}
