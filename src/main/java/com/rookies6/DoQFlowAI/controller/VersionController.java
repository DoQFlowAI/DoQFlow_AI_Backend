package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.VersionContentResponse;
import com.rookies6.DoQFlowAI.dto.response.VersionResponse;
import com.rookies6.DoQFlowAI.dto.response.VersionUploadResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.service.VersionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 버전 API < API 18, API 19, API 20, API 21, API 23 >
 *
 * 권한 판정은 VersionService가 AccessPolicy로 한다. 업로드·삭제는 등록자 본인만, 조회는 읽기 권한.
 */
@RestController
@RequestMapping("/documents/{documentId}/versions")
@RequiredArgsConstructor
public class VersionController {

	private final VersionService versionService;

	/**
	 * 버전 일괄 업로드 < API 18 > — multipart, 필드 이름 files (배열 순서 = 버전 순서). 201
	 * files가 없어도 400(FILE_COUNT)으로 같은 오류 형식이 나가도록 필수 검사는 서비스의 검증에 맡긴다.
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<VersionUploadResponse> upload(@CurrentUser AppUser me, @PathVariable Long documentId,
			@RequestParam(value = "files", required = false) List<MultipartFile> files) {
		return ApiResponse.ok(versionService.uploadBatch(me, documentId, files == null ? List.of() : files));
	}

	/** 버전 목록 < API 19 > */
	@GetMapping
	public ApiResponse<List<VersionResponse>> list(@CurrentUser AppUser me, @PathVariable Long documentId) {
		return ApiResponse.ok(versionService.list(me, documentId));
	}

	/** 버전 단건 < API 20 > */
	@GetMapping("/{versionNo}")
	public ApiResponse<VersionResponse> get(@CurrentUser AppUser me, @PathVariable Long documentId,
			@PathVariable int versionNo) {
		return ApiResponse.ok(versionService.get(me, documentId, versionNo));
	}

	/** 원문 보기 < API 23 > — DB의 content_text만 돌려준다. 파일 다운로드는 없다 */
	@GetMapping("/{versionNo}/content")
	public ApiResponse<VersionContentResponse> content(@CurrentUser AppUser me, @PathVariable Long documentId,
			@PathVariable int versionNo) {
		return ApiResponse.ok(versionService.getContent(me, documentId, versionNo));
	}

	/** 버전 삭제 < API 21 > — 등록자 본인만. 204 */
	@DeleteMapping("/{versionNo}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@CurrentUser AppUser me, @PathVariable Long documentId, @PathVariable int versionNo) {
		versionService.deleteVersion(me, documentId, versionNo);
	}
}
