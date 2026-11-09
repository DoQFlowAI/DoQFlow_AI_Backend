package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.exception.AccountLockedException;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 시도 제한 < API 4.2 >
 * 연속 5회 실패 시 15분 잠금. 값은 application.yaml의 app.login.* 에서 읽는다.
 */
@Service
@Transactional
public class LoginAttemptService {

	private final AppUserRepository users;
	private final int maxFailures;
	private final int lockMinutes;

	public LoginAttemptService(
			AppUserRepository users,
			@Value("${app.login.max-failures}") int maxFailures,
			@Value("${app.login.lock-minutes}") int lockMinutes) {
		this.users = users;
		this.maxFailures = maxFailures;
		this.lockMinutes = lockMinutes;
	}

	/** 잠긴 계정이면 423으로 막는다. 비밀번호를 대조하기 전에 부른다 */
	public void checkLocked(AppUser user) {
		if (user.isLocked()) {
			throw new AccountLockedException("userId=" + user.getId());
		}
	}

	/**
	 * 로그인 실패 횟수를 올리고, 임계치에 닿으면 계정을 잠근다.
	 *
	 * REQUIRES_NEW 인 이유: 호출자(AuthService.login)는 이 메서드를 부른 직후
	 * InvalidCredentialsException 을 던진다. 기본 REQUIRED 로 호출자의 트랜잭션에
	 * 참여하면 그 예외와 함께 롤백되어 증가분이 사라지고, failed_login_count 가
	 * 영원히 0 이라 잠금이 발동하지 않는다. 별도 트랜잭션에서 독립적으로 커밋해야 한다.
	 *
	 * AppUser 가 아니라 userId 를 받는 이유: REQUIRES_NEW 는 영속성 컨텍스트가
	 * 새로 열린다. 호출자가 넘긴 AppUser 는 바깥 컨텍스트 소유라 이 트랜잭션에서는
	 * 준영속(detached) 이고, 값을 바꿔도 더티 체킹/flush 대상이 아니다.
	 * 반드시 이 안에서 다시 조회한 엔티티를 변경해야 UPDATE 가 나간다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailure(Long userId) {
		users.findById(userId)
				.ifPresent(u -> u.recordLoginFailure(maxFailures, lockMinutes));
	}

	/** 로그인 성공. 호출자 트랜잭션이 정상 커밋되므로 REQUIRED 그대로 둔다 */
	public void recordSuccess(AppUser user) {
		user.recordLoginSuccess();
	}
}
