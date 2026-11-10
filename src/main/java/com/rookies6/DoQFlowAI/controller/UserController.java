package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.MeResponse;
import com.rookies6.DoQFlowAI.dto.response.UserLookupResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.exception.UserNotFoundException;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import com.rookies6.DoQFlowAI.security.CurrentUser;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 API < API 4.5, 5.1 > */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final AccessPolicy accessPolicy;
    private final AppUserRepository users;

    /**
     * 내 정보 < API 4.5 >
     * CurrentUserResolver가 이미 DB에서 읽어 주입하므로 다시 조회하지 않는다.
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@CurrentUser AppUser me) {
        return ApiResponse.ok(MeResponse.of(me));
    }

    /**
     * 이메일로 사용자 조회 < API 5.1 >
     *
     * <p>정확한 이메일 하나만 받는다. 부분 검색·전체 명부 조회가 아니다.
     * 직급 판정은 AccessPolicy.registerOrThrow를 재사용한다 —
     * "문서를 등록할 수 있는 사람 = 초대할 수 있는 사람"이라 판정이 같고,
     * 따로 만들면 두 곳이 어긋날 수 있다 (SEC-02).
     */
    @GetMapping("/lookup")
    public ApiResponse<UserLookupResponse> lookup(
            @CurrentUser AppUser me,
            @RequestParam @Email @NotBlank String email) {

        accessPolicy.registerOrThrow(me.getId());

        AppUser target = users.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("email lookup miss"));

        return ApiResponse.ok(UserLookupResponse.of(target));
    }
}
