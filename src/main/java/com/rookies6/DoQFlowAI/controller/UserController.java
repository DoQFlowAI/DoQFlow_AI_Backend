package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.MeResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 API < API 4.5, 5.1 > */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    /**
     * 내 정보 < API 4.5 >
     * CurrentUserResolver가 이미 DB에서 읽어 주입하므로 다시 조회하지 않는다.
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@CurrentUser AppUser me) {
        return ApiResponse.ok(MeResponse.of(me));
    }
}
