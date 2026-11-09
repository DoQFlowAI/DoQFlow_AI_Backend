package com.rookies6.DoQFlowAI.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yaml의 ai.* 설정
 *
 * @param baseUrl        AI Module 주소
 * @param internalKey
 * @param connectTimeout
 * @param readTimeout
 */
@ConfigurationProperties(prefix = "ai")
public record AiClientProperties(
		String baseUrl,
		String internalKey,
		Duration connectTimeout,
		Duration readTimeout
) {
}
