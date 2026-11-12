package com.rookies6.DoQFlowAI.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yaml의 ai.* 설정
 *
 * @param baseUrl            AI Module 주소
 * @param internalKey        X-Internal-Key 값 (환경변수로만 주입)
 * @param connectTimeout     연결 제한 (3초)
 * @param readTimeout        읽기 제한 (60초). extract, index, deleteIndex에 쓴다
 * @param historyReadTimeout /internal/history 읽기 제한 (180초).
 * AI Module이 구간마다 LLM을 부르고 실패 시 재시도해 60초를 넘길 수 있어 따로 둔다.
 * 설정이 없으면 readTimeout과 같다.
 */
@ConfigurationProperties(prefix = "ai")
public record AiClientProperties(
		String baseUrl,
		String internalKey,
		Duration connectTimeout,
		Duration readTimeout,
		Duration historyReadTimeout
) {

	public AiClientProperties {
		if (historyReadTimeout == null) {
			historyReadTimeout = readTimeout;
		}
	}
}
