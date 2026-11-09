package com.rookies6.DoQFlowAI.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 업로드 후처리 전용 스레드풀 설정 확인 < API 18 >
 *
 * DB 없이 실행 설정만 띄워 확인한다.
 */
class AsyncConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
			.withUserConfiguration(AsyncConfig.class);

	@Test
	void force_모드면_전용_풀과_기본_executor가_함께_있다() {
		runner.withPropertyValues("spring.task.execution.mode=force").run(context -> {
			assertThat(context).hasBean(AsyncConfig.UPLOAD_EXECUTOR);
			assertThat(context).hasBean("applicationTaskExecutor");
			assertThat(context.getBean("applicationTaskExecutor")).isInstanceOf(AsyncTaskExecutor.class);

			ThreadPoolTaskExecutor upload = context.getBean(AsyncConfig.UPLOAD_EXECUTOR, ThreadPoolTaskExecutor.class);
			assertThat(upload.getCorePoolSize()).isEqualTo(2);
			assertThat(upload.getMaxPoolSize()).isEqualTo(4);
			assertThat(upload.getThreadNamePrefix()).isEqualTo("upload-");
		});
	}

	@Test
	void 후처리_스레드에_요청의_traceId가_넘어가고_끝나면_지워진다() throws Exception {
		ThreadPoolTaskExecutor executor = new AsyncConfig().uploadExecutor();
		executor.initialize();
		try {
			MDC.put(TraceIdFilter.TRACE_ID, "trace-1");
			String seen = executor.submit(() -> MDC.get(TraceIdFilter.TRACE_ID)).get(5, TimeUnit.SECONDS);
			MDC.clear();
			// 같은 스레드를 다시 쓰는 다음 작업에는 이전 traceId가 남지 않는다
			String next = executor.submit(() -> MDC.get(TraceIdFilter.TRACE_ID)).get(5, TimeUnit.SECONDS);

			assertThat(seen).isEqualTo("trace-1");
			assertThat(next).isNull();
		} finally {
			MDC.clear();
			executor.shutdown();
		}
	}

	@Test
	void force_설정이_없으면_기본_executor가_사라진다() {
		// application.yaml의 spring.task.execution.mode: force를 지우면 안 되는 이유
		runner.run(context -> assertThat(context).doesNotHaveBean("applicationTaskExecutor"));
	}
}
