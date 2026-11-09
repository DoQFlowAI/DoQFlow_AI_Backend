package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.request.SignupRequest;
import com.rookies6.DoQFlowAI.dto.response.SignupResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.exception.EmailDuplicatedException;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증 < API 4.1~4.4 > */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

	private final AppUserRepository users;
	private final PasswordEncoder passwordEncoder;

	/**
	 * 회원가입 < API 4.1 >
	 *
	 * <p>signupKey는 검증하지 않는다 (SEC-B 재현). 추후 작업 예정
	 */
	@Transactional
	public SignupResponse signup(SignupRequest req) {
		if (users.existsByEmail(req.email())) {
			throw new EmailDuplicatedException("email=" + req.email());
		}

		AppUser user = AppUser.builder()
				.email(req.email())
				.passwordHash(passwordEncoder.encode(req.password()))
				.name(req.name())
				.rankLevel(req.rankLevel())
				.build();

		return SignupResponse.of(users.save(user));
	}
}
