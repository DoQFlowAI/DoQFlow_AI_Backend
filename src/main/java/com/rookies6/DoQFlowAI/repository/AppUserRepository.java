package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 사용자(app_user) 리포지토리 — 담당 홍서연
 *
 * <p>사용처
 * <ul>
 *   <li>[API A-1] 회원가입 — existsByEmail()</li>
 *   <li>[API A-2] 로그인 — findByEmail()</li>
 *   <li>[API A-3~5] 재발급·로그아웃·내 정보 — findByPublicId()</li>
 *   <li>[API P-1] 초대 대상 검색 — findByEmail()</li>
 *   <li>[AccessPolicy] 등록 권한 판정 — findById()</li>
 * </ul>
 *
 * <p>JWT subject는 publicId(UUID)다. 내부 PK(id)는 응답에 내보내지 않는다.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);

	/** JWT subject로 사용자를 찾는다 */
	Optional<AppUser> findByPublicId(String publicId);
}
