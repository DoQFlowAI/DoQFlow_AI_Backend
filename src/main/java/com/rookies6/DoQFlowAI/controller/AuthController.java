package com.rookies6.DoQFlowAI.controller;

import com.rookies6.DoQFlowAI.dto.request.LoginRequest;
import com.rookies6.DoQFlowAI.dto.request.SignupRequest;
import com.rookies6.DoQFlowAI.dto.request.TokenRequest;
import com.rookies6.DoQFlowAI.dto.response.ApiResponse;
import com.rookies6.DoQFlowAI.dto.response.SignupResponse;
import com.rookies6.DoQFlowAI.dto.response.TokenResponse;
import com.rookies6.DoQFlowAI.exception.BusinessException;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.security.RefreshTokenCookie;
import com.rookies6.DoQFlowAI.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API < API 4 > — 전부 SecurityConfig의 PUBLIC_PATHS거나 Refresh Token 기반이다
 *
 * Refresh Token은 HttpOnly 쿠키(RefreshTokenCookie)로만 주고받는다. 응답 본문에는 Access Token만 있다.
 * 재발급·로그아웃은 요청 본문 없이 호출하고, 브라우저가 쿠키를 자동으로 붙인다.
 * 회전·재사용 탐지·감사 로그는 AuthService가 그대로 한다.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final RefreshTokenCookie refreshCookie;

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ApiResponse.ok(authService.signup(request), "가입이 완료되었습니다.");
	}

	/** 로그인 — 본문 { accessToken, name, rankLevel } + Refresh Token 쿠키 */
	@PostMapping("/login")
	public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		TokenResponse tokens = authService.login(request);
		httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.issue(httpRequest, tokens.refreshToken()));
		return ApiResponse.ok(tokens);
	}

	/**
	 * 재발급 — 쿠키의 Refresh Token으로 새 Access Token과 새 Refresh Token 쿠키를 내린다 (회전).
	 * 쿠키가 없거나 무효면 401이고 쿠키를 지운다. 화면은 새로고침 직후 이것으로 로그인 상태를 되살린다.
	 */
	@PostMapping("/refresh")
	public ApiResponse<TokenResponse> refresh(
			@CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "no refresh token cookie");
		}
		TokenResponse tokens;
		try {
			tokens = authService.refresh(new TokenRequest(refreshToken));
		} catch (BusinessException e) {
			// 만료·폐기·재사용 탐지 — 더 쓸 수 없는 쿠키는 지운다
			httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.clear(httpRequest));
			throw e;
		}
		httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.issue(httpRequest, tokens.refreshToken()));
		return ApiResponse.ok(tokens);
	}

	/** 로그아웃 — 쿠키의 Refresh Token을 폐기하고 쿠키를 지운다. 쿠키가 없어도 성공 (멱등) */
	@PostMapping("/logout")
	public ApiResponse<Void> logout(
			@CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
			HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		if (refreshToken != null && !refreshToken.isBlank()) {
			authService.logout(new TokenRequest(refreshToken));
		}
		httpResponse.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.clear(httpRequest));
		return ApiResponse.ok("로그아웃되었습니다.");
	}
}
