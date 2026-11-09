package com.rookies6.DoQFlowAI.security;

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
 * <p>토큰 기반 무상태 API다. 세션·폼로그인·CSRF를 모두 끈다.
 * CSRF 토큰은 쿠키 기반 세션을 전제로 한 방어인데 여기서는 쿠키를 쓰지 않고
 * Authorization 헤더로만 인증하므로 해당되지 않는다.
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
	 * <p>allowCredentials는 false다. 쿠키를 쓰지 않고 Authorization 헤더로만 인증하므로
	 * 켤 이유가 없고, 켜면 와일드카드 출처를 쓸 수 없게 된다.
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
