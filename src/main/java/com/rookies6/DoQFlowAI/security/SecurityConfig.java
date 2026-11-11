package com.rookies6.DoQFlowAI.security;

import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 시큐리티 설정 — 공용 파일. 경로 규칙을 바꿀 때는 팀에 공유한다.
 *
 * <p>토큰 기반 무상태 API다. 세션·폼로그인·CSRF 토큰을 모두 끈다.
 * Access Token은 Authorization 헤더로만 받으므로 일반 API는 CSRF 대상이 아니다.
 *
 * <p><b>단, Refresh Token은 HttpOnly 쿠키로 오간다(RefreshTokenCookie).</b> 쿠키는 브라우저가 자동으로
 * 붙이므로 /auth/refresh·/auth/logout은 원칙적으로 CSRF 대상이다. 여기서는 CSRF 토큰 대신
 * 쿠키의 SameSite=Strict로 막는다. 쿠키 속성을 완화하거나(SameSite=None 등) 다른 출처에서
 * 호출하게 되면 이 전제가 깨지므로 CSRF 토큰을 다시 켜야 한다.
 *
 * <p><b>비밀번호 해시는 BCrypt여야 한다.</b> Spring Security 7 자료들이 Argon2를 권하지만
 * app_user.password_hash가 CHAR(60)이고 이는 BCrypt 출력 길이에 정확히 맞춘 것이다.
 * Argon2 해시는 95자를 넘어 들어가지 않는다. 인코더를 바꾸려면 스키마부터 V2로 바꿔야 한다.
 *
 * <p><b>/internal/** 은 denyAll이다.</b> Backend → AI Module 전용 경로이고
 * 외부에서 열릴 이유가 없다. Nginx에서도 막지만 두 겹으로 둔다 (02 §6).
 */
@Configuration
public class SecurityConfig {

	/** 인증 없이 열리는 경로. 그 외는 전부 인증이 필요하다 */
	private static final String[] PUBLIC_PATHS = {
			"/auth/signup",
			"/auth/login",
			"/auth/refresh",
			"/actuator/health",
			"/actuator/info"
	};

	private final JwtProvider jwtProvider;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;
	private final RestAccessDeniedHandler accessDeniedHandler;
	private final List<String> allowedOrigins;

	public SecurityConfig(JwtProvider jwtProvider,
			RestAuthenticationEntryPoint authenticationEntryPoint,
			RestAccessDeniedHandler accessDeniedHandler,
			@Value("${app.cors.allowed-origins:}") String allowedOrigins) {

		this.jwtProvider = jwtProvider;
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
		this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable())
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// 순서가 중요하다. 구체적인 규칙이 anyRequest보다 먼저 와야 한다
						//
						// ASYNC·ERROR는 외부 요청이 아니라 컨테이너가 같은 요청을 이어서 돌리는 내부 디스패치다.
						// Spring Security 6부터 인가 필터가 모든 디스패치 타입에 걸리는데, JwtAuthFilter는
						// OncePerRequestFilter라 ASYNC 재디스패치를 건너뛴다(shouldNotFilterAsyncDispatch 기본 true).
						// 그래서 SseEmitter를 반환하는 POST /ai/chat이 끝나며 재디스패치될 때
						// SecurityContext가 빈 채로 인가만 돌아 거부되고, 이미 SSE 헤더가 나간 뒤라
						// EntryPoint가 401을 쓰지 못해 "response is already committed"로 터진다.
						// ERROR도 같다. /error 포워딩이 PUBLIC_PATHS에 없어 같은 경로로 막힌다.
						// 최초 REQUEST 디스패치에서 이미 인가를 통과한 요청만 여기로 오므로 permitAll이 안전하다
						.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
						.requestMatchers("/internal/**").denyAll()
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(ex -> ex
						// 필터 단계의 401·403도 컨트롤러와 같은 오류 봉투로 내린다
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.addFilterBefore(new JwtAuthFilter(jwtProvider),
						UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/** app_user.password_hash가 CHAR(60) — BCrypt 출력 길이다. 위 주석 참고 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/**
	 * 허용 출처는 app.cors.allowed-origins(환경변수 CORS_ORIGINS)에서 읽는다.
	 *
	 * <p>allowCredentials는 false다. Refresh Token 쿠키를 쓰지만 화면과 API가 같은 출처(Nginx,
	 * 로컬은 Vite 프록시)라 CORS 요청이 아니고, 켜면 와일드카드 출처를 쓸 수 없게 된다.
	 * 화면을 다른 출처에 두게 되면 allowCredentials=true + 출처 명시 + 쿠키 SameSite=None이 한 묶음으로 필요하다.
	 *
	 * <p>X-Trace-Id를 노출해 프론트가 오류 신고 시 traceId를 함께 전달할 수 있게 한다.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		config.setExposedHeaders(List.of("X-Trace-Id"));
		config.setAllowCredentials(false);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
