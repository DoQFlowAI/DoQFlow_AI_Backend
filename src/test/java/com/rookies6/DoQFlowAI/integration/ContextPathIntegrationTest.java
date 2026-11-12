package com.rookies6.DoQFlowAI.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * API 경로 앞의 /api < Frontend 개발 명세서 3장 >
 *
 * 화면은 /api로만 요청하고 로컬 Vite 프록시는 /api를 떼지 않고 넘긴다. 실제 서버를 띄워 HTTP로 확인한다
 * (MockMvc는 context-path를 거치지 않아 이 설정을 확인할 수 없다).
 *
 * DB가 있을 때만 돈다 (다른 통합 테스트와 같은 환경변수).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class ContextPathIntegrationTest {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> System.getenv("DOQ_IT_DB_URL"));
		registry.add("spring.datasource.username", () -> System.getenv("DOQ_IT_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("DOQ_IT_DB_PASSWORD"));
		// 테스트 전용 값. 실제 비밀값을 쓰지 않는다
		registry.add("jwt.secret", () -> "integration-test-secret-integration-test-secret");
		registry.add("ai.internal-key", () -> "integration-test-key");
	}

	@LocalServerPort
	int port;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@DisplayName("API는 /api 아래에 있고 /api 없는 경로는 없다")
	void apiIsServedUnderApiPrefix() throws Exception {
		// /api 아래 경로에는 보안 필터가 동작한다 (토큰 없음 401)
		assertThat(get("/api/documents").statusCode()).isEqualTo(401);
		// /api가 없으면 앱 밖의 경로라 404
		assertThat(get("/documents").statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("헬스체크도 /api 아래에 있다")
	void healthIsUnderApiPrefix() throws Exception {
		HttpResponse<String> response = get("/api/actuator/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("UP");
	}

	private HttpResponse<String> postJson(String path, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json)).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@DisplayName("Refresh Token 쿠키는 /api/auth 경로에만 보내지도록 내려간다")
	void refreshCookiePathIncludesApiPrefix() throws Exception {
		String email = "staff-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		String credentials = "{\"email\":\"" + email + "\",\"password\":\"Passw0rd!2026\"";
		assertThat(postJson("/api/auth/signup", credentials + ",\"name\":\"staff\",\"rankLevel\":1}").statusCode())
				.isEqualTo(201);

		HttpResponse<String> login = postJson("/api/auth/login", credentials + "}");

		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(login.headers().firstValue("Set-Cookie")).hasValueSatisfying(cookie -> assertThat(cookie)
				.startsWith("doq_refresh=").contains("Path=/api/auth", "HttpOnly", "SameSite=Strict"));
		assertThat(login.body()).doesNotContain("refreshToken");
	}
}
