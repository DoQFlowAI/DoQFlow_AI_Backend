package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.request.LoginRequest;
import com.rookies6.DoQFlowAI.dto.request.SignupRequest;
import com.rookies6.DoQFlowAI.dto.response.SignupResponse;
import com.rookies6.DoQFlowAI.dto.response.TokenResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.RefreshToken;
import com.rookies6.DoQFlowAI.exception.EmailDuplicatedException;
import com.rookies6.DoQFlowAI.exception.InvalidCredentialsException;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import com.rookies6.DoQFlowAI.repository.RefreshTokenRepository;
import com.rookies6.DoQFlowAI.security.JwtProvider;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;



/** 인증 < API 4.1~4.4 > */
@Service
@Transactional(readOnly = true)
public class AuthService {

	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenRepository tokens;
	private final JwtProvider jwtProvider;
	private final LoginAttemptService loginAttempt;
	private final long refreshExpirationMillis;

  public AuthService(AppUserRepository users,
			RefreshTokenRepository tokens,
			PasswordEncoder passwordEncoder,
			JwtProvider jwtProvider,
			LoginAttemptService loginAttempt,
			@Value("${jwt.refresh-expiration}") long refreshExpirationMillis) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtProvider = jwtProvider;
		this.loginAttempt = loginAttempt;
		this.refreshExpirationMillis = refreshExpirationMillis;
	}

	/**
	 * 회원가입 < API 4.1 >
	 *
	 * <p>signupKey는 검증하지 않는다 (SEC-B 재현). 추후 작업 예정
	 */
	@Transactional
	public SignupResponse signup(SignupRequest req) {
		if (users.existsByEmail(req.email())) {
			throw new EmailDuplicatedException("email=" + req.email());
		}

		AppUser user = AppUser.builder()
				.email(req.email())
				.passwordHash(passwordEncoder.encode(req.password()))
				.name(req.name())
				.rankLevel(req.rankLevel())
				.build();

		return SignupResponse.of(users.save(user));
	}

  /**
	 * 로그인 < API 4.2 >
	 *
	 * <p>미가입과 비밀번호 불일치에 같은 예외를 쓴다. 응답이 갈리면
	 * 그 이메일이 가입돼 있는지 확인하는 수단이 된다.
	 */
	@Transactional
	public TokenResponse login(LoginRequest req) {
		AppUser user = users.findByEmail(req.email())
				.orElseThrow(() -> new InvalidCredentialsException("no such email"));

		loginAttempt.checkLocked(user);

		if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
			loginAttempt.recordFailure(user.getId());
			throw new InvalidCredentialsException("password mismatch: userId=" + user.getId());
		}

		loginAttempt.recordSuccess(user);
		return issueTokens(user);
	}

	/**
	 * Access Token과 Refresh Token을 함께 발급한다.
	 *
	 * <p>Refresh Token은 JWT가 아니라 임의 문자열이다. 원문은 응답으로만 주고
	 * DB에는 SHA-256 해시를 넣는다. token_hash가 CHAR(64)인 이유다.
	 */
	private TokenResponse issueTokens(AppUser user) {
		String accessToken = jwtProvider.createAccessToken(user.getPublicId());
		String refreshToken = randomToken();

		tokens.save(RefreshToken.builder()
				.user(user)
				.tokenHash(sha256(refreshToken))
				.expiresAt(LocalDateTime.now().plusNanos(refreshExpirationMillis * 1_000_000))
				.build());

		return TokenResponse.of(accessToken, refreshToken, user);
	}

	/** 256비트 난수. 예측 가능한 값이면 남의 세션을 만들어낼 수 있다 */
	private static String randomToken() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String sha256(String raw) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
