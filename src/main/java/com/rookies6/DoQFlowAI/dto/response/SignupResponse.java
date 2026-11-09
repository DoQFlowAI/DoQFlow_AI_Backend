package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;

/** 회원가입 응답 < API 4.1 > — 내부 PK가 아니라 publicId를 돌려준다 */
public record SignupResponse(String uuid) {

	public static SignupResponse of(AppUser user) {
		return new SignupResponse(user.getPublicId());
	}
}
