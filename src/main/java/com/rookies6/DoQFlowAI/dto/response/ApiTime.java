package com.rookies6.DoQFlowAI.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 응답 시각 변환 (API 설계서 1장 시각 포맷: ISO-8601 UTC, 예 2026-11-06T09:30:00Z)
 *
 * <p>엔티티의 시각은 LocalDateTime(DB DATETIME)이고 서버 시간대로 기록된다.
 * 응답에는 서버 시간대 기준으로 UTC 시각(Instant)으로 바꿔 넣는다. Jackson은 Instant를 끝에 Z가 붙은 문자열로 쓴다.
 * 로컬(KST)이든 Docker(UTC)든 같은 순간을 가리키는 값이 나간다.
 */
public final class ApiTime {

	private ApiTime() {
	}

	/** 값이 없으면(저장 전 엔티티 등) null을 그대로 돌려준다. */
	public static Instant toInstant(LocalDateTime dateTime) {
		return dateTime == null ? null : dateTime.atZone(ZoneId.systemDefault()).toInstant();
	}
}
