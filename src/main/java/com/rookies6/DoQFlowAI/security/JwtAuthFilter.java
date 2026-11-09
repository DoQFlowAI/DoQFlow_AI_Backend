package com.rookies6.DoQFlowAI.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization 헤더의 Bearer 토큰을 검증해 SecurityContext를 채운다.
 *
 * <p><b>principal에는 publicId를 담는다.</b> CurrentUserResolver가 getName()으로
 * 이 값을 읽어 DB를 조회하므로 둘의 약속이 어긋나면 인증이 조용히 깨진다.
 *
 * <p><b>권한 목록은 비워 둔다.</b> 직급을 토큰이나 GrantedAuthority로 옮기지 않는다.
 * 인가는 AccessPolicy가 요청마다 DB를 보고 판정한다 < R-2, SEC-02 >
 *
 * <p><b>토큰이 잘못돼도 여기서 응답을 쓰지 않는다.</b> SecurityContext를 비운 채 체인을 계속 보내고,
 * 인증이 필요한 경로면 뒤에서 ExceptionTranslationFilter가 EntryPoint를 호출해 401을 만든다.
 * 필터가 직접 응답을 쓰면 오류 봉투가 두 군데서 만들어져 형식이 갈라진다.
 * 토큰 없이도 되는 경로(가입·로그인)는 그대로 통과한다.
 *
 * <p>@Component를 붙이지 않는다. Filter 빈은 Boot가 서블릿 체인에도 자동 등록해서
 * 시큐리티 체인 밖에서 한 번 더 돌게 된다. SecurityConfig에서 직접 생성한다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final JwtProvider jwtProvider;

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String token = resolveToken(request);

		if (token != null) {
			try {
				String publicId = jwtProvider.parsePublicId(token);
				var authentication = new UsernamePasswordAuthenticationToken(
						publicId, null, List.of());
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} catch (JwtException | IllegalArgumentException e) {
				// 만료·위변조·형식 오류. 사유는 로그에만 남기고 응답에는 싣지 않는다
				SecurityContextHolder.clearContext();
				log.debug("invalid access token: {}", e.getClass().getSimpleName());
			}
		}

		filterChain.doFilter(request, response);
	}

	private String resolveToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER)) {
			return null;
		}
		String token = header.substring(BEARER.length()).trim();
		return token.isEmpty() ? null : token;
	}
}
