package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 < API 4.1 >
 *
 * <p>rankLevel을 요청에서 받는다. 사원(1)·팀장(2) 2단계다.
 *
 * <p>signupKey는 받기만 하고 <b>서버에서 검증하지 않는다.</b> ==> 키는 환경변수에 넣을 예정이며 추후 작업 예정
 * API를 직접 호출하면 키 없이도 팀장으로 가입할 수 있고, 그 상태가 보안점검서의
 * "조치 전" 근거다. Day 5에 이슈 I에서 키 대조를 넣는다. 지금 넣으면 증적이 사라진다.
 */
public record SignupRequest(

		@NotBlank
		@Email
		@Size(max = 255)
		String email,

		/** 영문·숫자·특수문자 중 2종 이상 < API 2.6 > */
		@NotBlank
		@Size(min = 8, max = 64)
		@Pattern(
				regexp = "^(?=.*[A-Za-z])(?=.*\\d).*$|^(?=.*[A-Za-z])(?=.*[^A-Za-z\\d]).*$|^(?=.*\\d)(?=.*[^A-Za-z\\d]).*$",
				message = "영문·숫자·특수문자 중 2종 이상을 포함해야 합니다.")
		String password,

		@NotBlank
		@Size(min = 1, max = 50)
		String name,

		@Min(1)
		@Max(2)
		int rankLevel,

		/** 화면에서는 rankLevel=2일 때 필수. 서버는 검증하지 않는다 (SEC-B) */
		String signupKey
) {
}
