package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;

/**
 * 로그인·재발급 응답 < API 4.2, 4.3 >
 * 화면이 직급에 따라 업로드·초대 버튼을 보여야 해서 name·rankLevel을 함께 내린다.
 */
public record TokenResponse(
		String accessToken,
		String refreshToken,
		String name,
		int rankLevel
) {

	public static TokenResponse of(String accessToken, String refreshToken, AppUser user) {
		return new TokenResponse(accessToken, refreshToken, user.getName(), user.getRankLevel());
	}
}
