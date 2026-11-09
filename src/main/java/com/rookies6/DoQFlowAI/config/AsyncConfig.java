package com.rookies6.DoQFlowAI.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;


/** 비동기 실행 설정 <API 18>
 * 비동기 처리 과정 ( 추출 -> diff -> 요약 -> 색인 )
 * 실행 스레드는 Spring Boot 기본을 사용
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
