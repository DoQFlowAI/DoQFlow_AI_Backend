package com.rookies6.DoQFlowAI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 앱 전체가 뜨는지 확인한다 (Flyway 마이그레이션·JPA 검증 포함).
 * DB가 있을 때만 돈다 (통합 테스트와 같은 환경변수). .env가 없는 CI에서도 단위 테스트가 막히지 않게 한다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOQ_IT_DB_URL", matches = ".+")
class DoQFlowAiApplicationTests {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> System.getenv("DOQ_IT_DB_URL"));
		registry.add("spring.datasource.username", () -> System.getenv("DOQ_IT_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("DOQ_IT_DB_PASSWORD"));
		// 테스트 전용 값. 실제 비밀값을 쓰지 않는다
		registry.add("jwt.secret", () -> "integration-test-secret-integration-test-secret");
		registry.add("ai.internal-key", () -> "integration-test-key");
	}

	@Test
	void contextLoads() {
	}

}
