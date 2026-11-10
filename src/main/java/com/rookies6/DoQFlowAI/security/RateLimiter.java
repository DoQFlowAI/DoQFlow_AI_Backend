package com.rookies6.DoQFlowAI.security;

import com.rookies6.DoQFlowAI.exception.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사용자당 분당 호출 횟수 제한 < API 설계서 2.4, P-1, Q-3 >
 *
 * 질의응답(POST /ai/chat)은 분당 20회, 이메일 사용자 조회(GET /users/lookup)는 분당 30회.
 * 넘으면 429 RATE_LIMIT_EXCEEDED.
 *
 * 방식 — 사용자·대상별로 최근 1분 안의 요청 시각을 모아 두고 개수를 센다 (슬라이딩 윈도).
 * 고정 구간(매분 0초에 초기화)으로 세면 59초와 61초 사이에 한도의 두 배가 통과한다.
 * 막힌 요청은 세지 않는다. 계속 두드려도 1분이 지나면 다시 쓸 수 있어야 한다.
 *
 * 메모리에만 둔다. Backend가 한 대(Compose)라 충분하고, 재시작하면 초기화된다.
 * 1분이 지난 기록은 그 사용자를 확인할 때 지운다. 더 이상 요청하지 않는 사용자의 항목은
 * 요청 SWEEP_EVERY번마다 한 번씩 전체를 훑어 지운다. 남는 기록은 사용자·대상당 최대 한도 개수(20, 30)다.
 */
@Component
public class RateLimiter {

	static final Duration WINDOW = Duration.ofMinutes(1);
	static final int SWEEP_EVERY = 1000;

	/** 제한 대상. 대상마다 따로 센다 */
	public enum Target {
		CHAT,
		LOOKUP
	}

	private final Clock clock;
	private final int chatPerMinute;
	private final int lookupPerMinute;
	private final Map<Key, Deque<Long>> requests = new ConcurrentHashMap<>();
	private final AtomicInteger calls = new AtomicInteger();

	@Autowired
	public RateLimiter(@Value("${app.rate-limit.chat-per-minute:20}") int chatPerMinute,
			@Value("${app.rate-limit.lookup-per-minute:30}") int lookupPerMinute) {
		this(Clock.systemUTC(), chatPerMinute, lookupPerMinute);
	}

	RateLimiter(Clock clock, int chatPerMinute, int lookupPerMinute) {
		this.clock = clock;
		this.chatPerMinute = chatPerMinute;
		this.lookupPerMinute = lookupPerMinute;
	}

	/** 질의응답 한 번. 한도를 넘으면 429 */
	public void checkChat(Long userId) {
		acquire(Target.CHAT, userId);
	}

	/** 이메일 사용자 조회 한 번. 한도를 넘으면 429 */
	public void checkLookup(Long userId) {
		acquire(Target.LOOKUP, userId);
	}

	/**
	 * 한도 안이면 이번 요청을 기록하고, 넘으면 기록하지 않고 막는다.
	 *
	 * @throws RateLimitExceededException 최근 1분 요청이 한도에 도달함 (429)
	 */
	void acquire(Target target, Long userId) {
		int limit = target == Target.CHAT ? chatPerMinute : lookupPerMinute;
		long now = clock.millis();
		long windowStart = now - WINDOW.toMillis();
		Key key = new Key(target, userId);

		boolean[] allowed = {false};
		// compute는 같은 키에 대해 한 번에 하나만 실행된다. 같은 사용자의 동시 요청이 한도를 함께 넘지 않는다
		requests.compute(key, (k, times) -> {
			Deque<Long> recent = times == null ? new ArrayDeque<>() : times;
			prune(recent, windowStart);
			if (recent.size() < limit) {
				recent.addLast(now);
				allowed[0] = true;
			}
			return recent.isEmpty() ? null : recent;
		});
		if (calls.incrementAndGet() % SWEEP_EVERY == 0) {
			sweep();
		}
		if (!allowed[0]) {
			throw new RateLimitExceededException(target + " limit " + limit + "/min userId=" + userId);
		}
	}

	/** 1분 안에 요청이 없는 사용자·대상 항목을 지운다. 키마다 compute로 잠그므로 진행 중인 확인과 겹치지 않는다 */
	void sweep() {
		long windowStart = clock.millis() - WINDOW.toMillis();
		for (Key key : requests.keySet()) {
			requests.computeIfPresent(key, (k, recent) -> {
				prune(recent, windowStart);
				return recent.isEmpty() ? null : recent;
			});
		}
	}

	private static void prune(Deque<Long> recent, long windowStart) {
		while (!recent.isEmpty() && recent.peekFirst() <= windowStart) {
			recent.pollFirst();
		}
	}

	/** 테스트용: 지금 기록이 남아 있는 사용자·대상 수 */
	int trackedKeys() {
		return requests.size();
	}

	private record Key(Target target, Long userId) {
	}
}
