package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.PermissionResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.service.PermissionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 열람 권한 관리 < API 5.7, 5.8 > */
@RestController
@RequestMapping("/documents/{documentId}/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /** 열람자 목록 < API 5.7 > — 등록자만 */
    @GetMapping
    public ApiResponse<List<PermissionResponse>> list(
            @CurrentUser AppUser me,
            @PathVariable Long documentId) {

        return ApiResponse.ok(permissionService.list(me.getId(), documentId));
    }
}