package com.rookies6.DoQFlowAI.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rookies6.DoQFlowAI.entity.AppUser;

/**
 * 로그인·재발급 응답 < API 4.2, 4.3 >
 * 화면이 직급에 따라 업로드·초대 버튼을 보여야 해서 name·rankLevel을 함께 내린다.
 *
 * refreshToken은 응답 본문(JSON)에 넣지 않는다. AuthController가 HttpOnly 쿠키로만 내린다 (RefreshTokenCookie).
 * 본문 JSON: { accessToken, name, rankLevel }
 */
public record TokenResponse(
		String accessToken,
		@JsonIgnore String refreshToken,
		String name,
		int rankLevel
) {

	public static TokenResponse of(String accessToken, String refreshToken, AppUser user) {
		return new TokenResponse(accessToken, refreshToken, user.getName(), user.getRankLevel());
	}
}
