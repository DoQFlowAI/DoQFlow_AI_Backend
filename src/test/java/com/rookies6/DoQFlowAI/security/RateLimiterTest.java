package com.rookies6.DoQFlowAI.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.exception.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 사용자당 분당 호출 횟수 제한 < API 설계서 2.4, P-1, Q-3 > */
class RateLimiterTest {

	private MutableClock clock;
	private RateLimiter limiter;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));
		limiter = new RateLimiter(clock, 20, 30);
	}

	private static void repeat(int times, Runnable action) {
		for (int i = 0; i < times; i++) {
			action.run();
		}
	}

	@Test
	@DisplayName("질문은 1분에 20번까지 되고 21번째는 429")
	void chatAllowsTwentyPerMinute() {
		repeat(20, () -> limiter.checkChat(1L));

		assertThatThrownBy(() -> limiter.checkChat(1L))
				.isInstanceOf(RateLimitExceededException.class)
				.extracting(e -> ((RateLimitExceededException) e).getErrorCode())
				.isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
	}

	@Test
	@DisplayName("사용자 조회는 1분에 30번까지 된다")
	void lookupAllowsThirtyPerMinute() {
		repeat(30, () -> limiter.checkLookup(1L));

		assertThatThrownBy(() -> limiter.checkLookup(1L)).isInstanceOf(RateLimitExceededException.class);
	}

	@Test
	@DisplayName("가장 오래된 요청이 1분을 넘기면 한 번 더 할 수 있다 (매분 0초에 초기화하지 않는다)")
	void slidingWindowFreesOldestRequest() {
		limiter.checkChat(1L);
		clock.advance(Duration.ofSeconds(30));
		repeat(19, () -> limiter.checkChat(1L));
		assertThatThrownBy(() -> limiter.checkChat(1L)).isInstanceOf(RateLimitExceededException.class);

		// 첫 요청이 1분을 넘긴 순간 한 자리만 빈다
		clock.advance(Duration.ofSeconds(30));
		assertThatCode(() -> limiter.checkChat(1L)).doesNotThrowAnyException();
		assertThatThrownBy(() -> limiter.checkChat(1L)).isInstanceOf(RateLimitExceededException.class);
	}

	@Test
	@DisplayName("막힌 요청은 세지 않아 계속 두드려도 1분 뒤에는 다시 된다")
	void rejectedRequestsAreNotCounted() {
		repeat(20, () -> limiter.checkChat(1L));
		for (int i = 0; i < 50; i++) {
			assertThatThrownBy(() -> limiter.checkChat(1L)).isInstanceOf(RateLimitExceededException.class);
		}

		clock.advance(Duration.ofMinutes(1));
		assertThatCode(() -> limiter.checkChat(1L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("사용자와 대상마다 따로 센다")
	void countsPerUserAndTarget() {
		repeat(20, () -> limiter.checkChat(1L));

		assertThatCode(() -> limiter.checkChat(2L)).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkLookup(1L)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("1분 동안 요청이 없는 사용자 기록은 정리할 때 지우고 최근 사용자는 남긴다")
	void sweepRemovesIdleUsers() {
		for (long userId = 1; userId <= 5; userId++) {
			limiter.checkChat(userId);
			limiter.checkLookup(userId);
		}
		clock.advance(Duration.ofMinutes(2));
		limiter.checkChat(9L);
		assertThat(limiter.trackedKeys()).isEqualTo(11);

		limiter.sweep();

		assertThat(limiter.trackedKeys()).isEqualTo(1);
	}

	@Test
	@DisplayName("요청이 일정 횟수 쌓이면 스스로 정리한다")
	void sweepsAutomatically() {
		RateLimiter big = new RateLimiter(clock, RateLimiter.SWEEP_EVERY, 30);
		for (long userId = 1; userId <= 10; userId++) {
			big.checkLookup(userId);
		}
		clock.advance(Duration.ofMinutes(2));

		repeat(RateLimiter.SWEEP_EVERY - 10, () -> big.checkChat(99L));

		assertThat(big.trackedKeys()).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 사용자가 동시에 보내도 한도를 넘겨 통과하지 않는다")
	void concurrentRequestsDoNotExceedLimit() throws Exception {
		int threads = 50;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger allowed = new AtomicInteger();
		for (int i = 0; i < threads; i++) {
			pool.submit(() -> {
				start.await();
				try {
					limiter.checkChat(1L);
					allowed.incrementAndGet();
				} catch (RateLimitExceededException ignored) {
					// 한도 초과
				}
				return null;
			});
		}
		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

		assertThat(allowed).hasValue(20);
	}

	/** 테스트에서 시간을 직접 움직이는 시계 */
	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
