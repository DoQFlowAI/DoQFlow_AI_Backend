package com.rookies6.DoQFlowAI.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access Token 발급과 검증 < API 1 >
 *
 * <p><b>subject는 app_user.public_id(UUID)다.</b> 내부 PK(id)를 넣지 않는다.
 * 내부 PK가 토큰에 들어가면 다른 사용자의 id를 추측할 수 있게 된다.
 *
 * <p><b>직급·역할은 토큰에 넣지 않는다.</b> 인가 판정은 요청마다 DB의 rank_level로 한다.
 * 토큰에 직급을 담으면 직급이 바뀐 뒤에도 기존 토큰이 만료될 때까지 예전 권한으로 통과한다 < R-2 >
 *
 * <p>만료는 15분이다. 값은 application.yaml의 jwt.access-expiration에서 읽는다.
 *
 * <p>Refresh Token은 여기서 만들지 않는다. refresh_token.token_hash가 CHAR(64),
 * 즉 SHA-256 16진수 길이이므로 Refresh Token은 JWT가 아니라 임의 문자열이고
 * 그 해시만 저장한다. DB가 새어도 토큰 자체는 쓸 수 없다. 발급은 AuthService 몫이다.
 */
@Component
public class JwtProvider {

	/** HS256은 최소 32바이트 키를 요구한다. 짧으면 jjwt가 WeakKeyException을 던진다 */
	private static final int MIN_SECRET_BYTES = 32;

	private final SecretKey key;
	private final long accessExpirationMillis;

	public JwtProvider(
			@Value("${jwt.secret}") String secret,
			@Value("${jwt.access-expiration}") long accessExpirationMillis) {

		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_SECRET_BYTES) {
			// 기동 시점에 막는다. 런타임에 터지면 원인을 찾기 어렵다
			throw new IllegalStateException(
					"JWT_SECRET must be at least " + MIN_SECRET_BYTES
							+ " bytes for HS256 (current: " + bytes.length + ")");
		}
		this.key = Keys.hmacShaKeyFor(bytes);
		this.accessExpirationMillis = accessExpirationMillis;
	}

	/** @param publicId app_user.public_id (UUID) */
	public String createAccessToken(String publicId) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(publicId)
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plusMillis(accessExpirationMillis)))
				.signWith(key)
				.compact();
	}

	/**
	 * 토큰을 검증하고 subject(publicId)를 돌려준다.
	 *
	 * <p>서명 위변조, 만료, 형식 오류를 모두 io.jsonwebtoken.JwtException 계열로 던진다.
	 * 호출하는 쪽(JwtAuthFilter)에서 한 번에 잡아 401로 처리한다.
	 *
	 * @throws io.jsonwebtoken.JwtException 토큰이 유효하지 않을 때
	 */
	public String parsePublicId(String token) {
		return Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload()
				.getSubject();
	}
}
