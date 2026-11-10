package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.InvitationResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import com.rookies6.DoQFlowAI.service.InvitationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 받은 초대 < API 5.9 >
 *
 * <p>경로가 /documents 아래가 아니다. 초대받은 사람은 수락 전까지 그 문서에
 * 접근할 수 없으므로 문서 경로에 둘 수 없다.
 */
@RestController
@RequestMapping("/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    /** 받은 초대 목록 < API 5.9 > — 본인의 PENDING만 */
    @GetMapping
    public ApiResponse<List<InvitationResponse>> list(@CurrentUser AppUser me) {
        return ApiResponse.ok(invitationService.list(me.getId()));
    }
}