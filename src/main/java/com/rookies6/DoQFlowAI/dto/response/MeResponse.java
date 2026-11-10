package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;
import java.time.LocalDateTime;

/** 내 정보 < API 4.5 > — 본인 정보이므로 이메일을 마스킹하지 않는다 */
public record MeResponse(
        String uuid,
        String email,
        String name,
        int rankLevel,
        LocalDateTime createdAt
) {

    public static MeResponse of(AppUser user) {
        return new MeResponse(
                user.getPublicId(), user.getEmail(), user.getName(),
                user.getRankLevel(), user.getCreatedAt());
    }
}
