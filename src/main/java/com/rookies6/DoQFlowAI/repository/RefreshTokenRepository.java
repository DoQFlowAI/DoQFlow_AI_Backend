package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.RefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Refresh Token 리포지토리 — 담당 홍서연
 *
 * <p>token_hash는 SHA-256 16진수 64자다. 토큰 원문은 저장하지 않으므로
 * DB가 유출돼도 세션을 탈취할 수 없다.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * 그 사용자의 살아 있는 토큰을 전부 폐기한다 < API 4.3 >
	 * 폐기된 토큰이 다시 들어오면 탈취로 보고 이걸 호출한다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("update RefreshToken t set t.revoked = true where t.user.id = :userId and t.revoked = false")
	int revokeAllByUserId(@Param("userId") Long userId);
}
