package com.rookies6.DoQFlowAI.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 쿠키 < API 4.2~4.4 >
 *
 * Refresh Token은 응답 본문이 아니라 이 쿠키로만 주고받는다. 화면의 JavaScript는 이 값을 읽을 수 없다.
 *   HttpOnly        JS(document.cookie)로 읽을 수 없다. XSS가 있어도 Refresh Token은 빼낼 수 없다
 *   Secure          HTTPS에서만 보낸다 (localhost는 브라우저가 예외로 허용). HTTPS가 없는 환경만 설정으로 끈다
 *   SameSite=Strict 다른 사이트에서 시작한 요청에는 붙지 않는다. 재발급·로그아웃 CSRF를 막는다
 *   Path=/api/auth  인증 API에만 보낸다. 다른 API 요청에는 실리지 않는다
 *   Max-Age         Refresh Token 만료(jwt.refresh-expiration)와 같다
 * 화면과 API가 같은 출처(Nginx, 로컬은 Vite 프록시)라 브라우저가 자동으로 붙인다. CORS 설정은 필요 없다.
 */
@Component
public class RefreshTokenCookie {

	public static final String NAME = "doq_refresh";

	private final boolean secure;
	private final Duration maxAge;

	public RefreshTokenCookie(@Value("${app.auth.refresh-cookie-secure:true}") boolean secure,
			@Value("${jwt.refresh-expiration}") long refreshExpirationMillis) {
		this.secure = secure;
		this.maxAge = Duration.ofMillis(refreshExpirationMillis);
	}

	/** 새 Refresh Token을 담는 Set-Cookie 값 */
	public String issue(HttpServletRequest request, String refreshToken) {
		return build(request, refreshToken, maxAge);
	}

	/** 브라우저의 Refresh Token 쿠키를 지우는 Set-Cookie 값 (로그아웃·재발급 실패) */
	public String clear(HttpServletRequest request) {
		return build(request, "", Duration.ZERO);
	}

	private String build(HttpServletRequest request, String value, Duration age) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				// context-path(/api)가 바뀌어도 인증 API 경로와 맞게 요청에서 읽는다
				.path(request.getContextPath() + "/auth")
				.maxAge(age)
				.build()
				.toString();
	}
}
