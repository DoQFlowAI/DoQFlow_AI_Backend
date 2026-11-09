package com.rookies6.DoQFlowAI.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;


/** 업로드 후처리 전용 스레드풀 <API 18>
 * 비동기 처리 과정 ( 추출 -> diff -> 요약 -> 색인 ) 은 @Async(AsyncConfig.UPLOAD_EXECUTOR) 로 이 풀에서만 돈다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	public static final String UPLOAD_EXECUTOR = "uploadExecutor";

	/**
	 * 동시에 2개, 최대 4개까지 후처리한다. 나머지는 대기열(50개)에서 기다린다.
	 * 종료 시에는 진행 중인 후처리가 끝나기를 최대 60초 기다린다.
	 */
	@Bean(name = UPLOAD_EXECUTOR)
	public ThreadPoolTaskExecutor uploadExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("upload-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		return executor;
	}
}
