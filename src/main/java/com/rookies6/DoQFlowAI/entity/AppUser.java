package com.rookies6.DoQFlowAI.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "app_user")
public class AppUser extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, updatable = false, length = 36)
	private String publicId;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(nullable = false, length = 60)
	private String passwordHash;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(nullable = false)
	private int rankLevel;

	@Column(nullable = false)
	private int failedLoginCount;

	private LocalDateTime lockedUntil;

	@Builder
	private AppUser(String email, String passwordHash, String name, int rankLevel) {
		this.publicId = UUID.randomUUID().toString();
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
		this.rankLevel = rankLevel;
	}

		/** 잠금 중인가 < API 4.2 > */
	public boolean isLocked() {
		return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
	}

	/**
	 * 로그인 실패 기록 < API 4.2 >
	 * maxFailures 도달 시 lockMinutes 만큼 잠그고 카운트를 0으로 되돌린다.
	 * 카운트를 남겨두면 잠금이 풀린 직후 한 번만 틀려도 다시 잠긴다.
	 */
	public void recordLoginFailure(int maxFailures, int lockMinutes) {
		failedLoginCount++;
		if (failedLoginCount >= maxFailures) {
			lockedUntil = LocalDateTime.now().plusMinutes(lockMinutes);
			failedLoginCount = 0;
		}
	}

	/** 로그인 성공 — 카운트와 잠금 해제 */
	public void recordLoginSuccess() {
		failedLoginCount = 0;
		lockedUntil = null;
	}
}
