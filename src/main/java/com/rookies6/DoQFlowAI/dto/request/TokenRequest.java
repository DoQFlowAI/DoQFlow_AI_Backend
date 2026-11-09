package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 재발급·로그아웃 공용 < API 4.3, 4.4 > */
public record TokenRequest(
        @NotBlank String refreshToken
) {
}
