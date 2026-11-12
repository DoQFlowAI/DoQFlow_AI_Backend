package com.rookies6.DoQFlowAI.entity;

/**
 * 직급 2단계 — 사원(1) · 팀장(2)
 *
 * <p>app_user.rank_level은 INT이고 CHECK (rank_level BETWEEN 1 AND 2)가 걸려 있다.
 * AppUser는 이 값을 int로 들고 있으므로 이 enum은 JPA 매핑이 아니라
 * 판정부에서 숫자 대신 이름을 쓰기 위한 것이다.
 *
 * <p>별도의 role 컬럼은 두지 않는다. 권한 분기는 직급과 "그 문서의 등록자인지"
 * 두 가지로만 결정한다.
 */
public enum RankLevel {

	STAFF(1),
	LEAD(2);

	private final int level;

	RankLevel(int level) {
		this.level = level;
	}

	public int level() {
		return level;
	}

	public static RankLevel of(int level) {
		for (RankLevel r : values()) {
			if (r.level == level) {
				return r;
			}
		}
		throw new IllegalArgumentException("unknown rankLevel: " + level);
	}
}
