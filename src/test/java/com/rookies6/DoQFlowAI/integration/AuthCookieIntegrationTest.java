package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.rookies6.DoQFlowAI.security.RefreshTokenCookie;
import com.rookies6.DoQFlowAI.service.AiClientService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refresh Token HttpOnly 쿠키 통합 테스트 < API 4.2~4.4 >
 *
 * 실제 MariaDB, 실제 보안 필터, 실제 AuthController·AuthService로 HTTP 요청을 보낸다.
 * 확인하는 것 — 쿠키 속성(HttpOnly·Secure·SameSite·Path), 본문에 Refresh Token 없음, 쿠키만으로 재발급(회전),
 * 옛 쿠키 재사용 시 401과 전부 폐기, 쿠키 없음 401, 로그아웃 시 폐기·삭제.
 *
 * DB가 있을 때만 돈다 (다른 통합 테스트와 같은 환경변수).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class AuthCookieIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PASSWORD = "Passw0rd!2026";

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> System.getenv("DOQ_IT_DB_URL"));
		registry.add("spring.datasource.username", () -> System.getenv("DOQ_IT_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("DOQ_IT_DB_PASSWORD"));
		// 테스트 전용 값. 실제 비밀값을 쓰지 않는다
		registry.add("jwt.secret", () -> "integration-test-secret-integration-test-secret");
		registry.add("ai.internal-key", () -> "integration-test-key");
	}

	@MockitoBean
	AiClientService ai;

	@Autowired MockMvc mvc;

	private String email;

	@BeforeEach
	void signUp() throws Exception {
		email = "staff-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		int status = mvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD,
								"name", "사원", "rankLevel", 1))))
				.andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(201);
	}

	private MockHttpServletResponse login() throws Exception {
		return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
				.andReturn().getResponse();
	}

	private MockHttpServletResponse refresh(String cookieValue) throws Exception {
		var request = post("/auth/refresh");
		if (cookieValue != null) {
			request.cookie(new Cookie(RefreshTokenCookie.NAME, cookieValue));
		}
		return mvc.perform(request).andReturn().getResponse();
	}

	private static String setCookie(MockHttpServletResponse response) {
		List<String> headers = response.getHeaders(HttpHeaders.SET_COOKIE);
		assertThat(headers).hasSize(1);
		return headers.get(0);
	}

	private static String cookieValue(MockHttpServletResponse response) {
		String header = setCookie(response);
		return header.substring((RefreshTokenCookie.NAME + "=").length(), header.indexOf(';'));
	}

	private static JsonNode data(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("data");
	}

	@Test
	@DisplayName("로그인하면 Refresh Token은 HttpOnly 쿠키로만 내리고 본문에는 Access Token만 있다")
	void loginSetsHttpOnlyCookieAndHidesRefreshTokenInBody() throws Exception {
		MockHttpServletResponse response = login();

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(setCookie(response))
				.startsWith(RefreshTokenCookie.NAME + "=")
				.contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/auth", "Max-Age=1209600");
		assertThat(cookieValue(response)).isNotBlank();

		JsonNode data = data(response);
		assertThat(data.get("accessToken").asString()).isNotBlank();
		assertThat(data.get("rankLevel").asInt()).isEqualTo(1);
		assertThat(data.has("refreshToken")).isFalse();
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(cookieValue(response));
	}

	@Test
	@DisplayName("쿠키만으로 재발급하면 새 Access Token과 새 쿠키를 받고, 새 Access Token으로 API를 쓸 수 있다")
	void refreshWithCookieRotatesToken() throws Exception {
		String first = cookieValue(login());

		MockHttpServletResponse refreshed = refresh(first);

		assertThat(refreshed.getStatus()).isEqualTo(200);
		String second = cookieValue(refreshed);
		assertThat(second).isNotEqualTo(first);
		assertThat(data(refreshed).has("refreshToken")).isFalse();

		String accessToken = data(refreshed).get("accessToken").asString();
		int me = mvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andReturn().getResponse().getStatus();
		assertThat(me).isEqualTo(200);
	}

	@Test
	@DisplayName("이미 쓴 쿠키를 다시 보내면 401이고 쿠키를 지우며 그 사용자의 토큰이 전부 폐기된다")
	void reusedCookieIs401AndRevokesAll() throws Exception {
		String first = cookieValue(login());
		String second = cookieValue(refresh(first));

		MockHttpServletResponse reused = refresh(first);

		assertThat(reused.getStatus()).isEqualTo(401);
		assertThat(setCookie(reused)).contains("Max-Age=0");
		// 재사용 탐지로 정상 사용자의 최신 토큰도 폐기된다 (탈취를 알아차리게)
		assertThat(refresh(second).getStatus()).isEqualTo(401);
	}

	@Test
	@DisplayName("쿠키 없이 재발급하면 401")
	void refreshWithoutCookieIs401() throws Exception {
		MockHttpServletResponse response = refresh(null);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8))
				.get("error").get("code").asString()).isEqualTo("UNAUTHORIZED");
	}

	@Test
	@DisplayName("로그아웃하면 쿠키의 토큰을 폐기하고 쿠키를 지운다")
	void logoutRevokesAndClearsCookie() throws Exception {
		MockHttpServletResponse loggedIn = login();
		String cookie = cookieValue(loggedIn);
		String accessToken = data(loggedIn).get("accessToken").asString();

		MockHttpServletResponse loggedOut = mvc.perform(post("/auth/logout")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
						.cookie(new Cookie(RefreshTokenCookie.NAME, cookie)))
				.andReturn().getResponse();

		assertThat(loggedOut.getStatus()).isEqualTo(200);
		assertThat(setCookie(loggedOut)).startsWith(RefreshTokenCookie.NAME + "=;").contains("Max-Age=0");
		assertThat(refresh(cookie).getStatus()).isEqualTo(401);
	}
}
