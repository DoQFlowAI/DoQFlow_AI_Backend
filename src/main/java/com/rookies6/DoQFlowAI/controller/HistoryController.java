package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.request.CompareRequest;
import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.CompareResponse;
import com.rookies6.DoQFlowAI.dto.response.HistoryResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.service.VersionHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 변천사·두 버전 비교 API < API 22, API 24 >
 * 저장된 값과 diff 계산만 쓰고 LLM은 호출하지 않는다.
 */
@RestController
@RequestMapping("/documents/{documentId}")
@RequiredArgsConstructor
public class HistoryController {

	private final VersionHistoryService historyService;

	/** 변천사 타임라인 < API 22 > */
	@GetMapping("/history")
	public ApiResponse<HistoryResponse> history(@CurrentUser AppUser me, @PathVariable Long documentId) {
		return ApiResponse.ok(historyService.history(me, documentId));
	}

	/** 두 버전 비교 < API 24 > - 없거나 같은 번호면 400 */
	@GetMapping("/compare")
	public ApiResponse<CompareResponse> compare(@CurrentUser AppUser me, @PathVariable Long documentId,
			@Valid @ModelAttribute CompareRequest request) {
		return ApiResponse.ok(historyService.compare(me, documentId, request.from(), request.to()));
	}
}
