package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.request.DocumentCreateRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentListRequest;
import com.rookies6.DoQFlowAI.dto.request.DocumentUpdateRequest;
import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentCreateResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentDetailResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentSummaryResponse;
import com.rookies6.DoQFlowAI.dto.response.DocumentUpdateResponse;
import com.rookies6.DoQFlowAI.dto.response.PageResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문서 API < API 7, API 8, API 9, API 10, API 11 >
 *
 * 권한 판정은 DocumentService가 AccessPolicy로 한다. 컨트롤러는 사용자 ID를 요청에서 받지 않고 토큰의 사용자만 쓴다.
 */
@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

	private final DocumentService documentService;

	/**
	 * 문서 생성 + 초대 < API 7 > — 팀장만 (사원은 403). 201
	 * 미가입 이메일은 오류가 아니라 응답의 notFound로 알려 준다.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<DocumentCreateResponse> create(@CurrentUser AppUser me,
			@Valid @RequestBody DocumentCreateRequest request) {
		return ApiResponse.ok(documentService.create(me, request));
	}

	/** 문서 목록 < API 8 > — 내가 등록했거나 초대를 수락한 문서만 */
	@GetMapping
	public ApiResponse<PageResponse<DocumentSummaryResponse>> list(@CurrentUser AppUser me,
			@Valid @ModelAttribute DocumentListRequest request) {
		return ApiResponse.ok(documentService.list(me, request));
	}

	/** 문서 단건 < API 9 > — 없는 문서·권한 밖은 404 */
	@GetMapping("/{documentId}")
	public ApiResponse<DocumentDetailResponse> get(@CurrentUser AppUser me, @PathVariable Long documentId) {
		return ApiResponse.ok(documentService.get(me, documentId));
	}

	/** 문서 이름·설명 수정 < API 10 > — 등록자 본인만 (아니면 403) */
	@PatchMapping("/{documentId}")
	public ApiResponse<DocumentUpdateResponse> update(@CurrentUser AppUser me, @PathVariable Long documentId,
			@Valid @RequestBody DocumentUpdateRequest request) {
		return ApiResponse.ok(documentService.update(me, documentId, request));
	}

	/** 문서 삭제 < API 11 > — 등록자 본인만. 204 */
	@DeleteMapping("/{documentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@CurrentUser AppUser me, @PathVariable Long documentId) {
		documentService.delete(me, documentId);
	}
}
