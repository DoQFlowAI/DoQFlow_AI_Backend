package com.rookies6.DoQFlowAI.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 두 버전 비교 요청 검증 단위 테스트 < API 24 > — 위반이 있으면 컨트롤러에서 400이 된다. */
class CompareRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("서로 다른 두 버전이면 통과")
	void passesForTwoDifferentVersions() {
		assertThat(validator.validate(new CompareRequest(1, 4))).isEmpty();
		assertThat(validator.validate(new CompareRequest(4, 1))).isEmpty();
	}

	@Test
	@DisplayName("같은 버전이면 실패")
	void failsForSameVersion() {
		assertThat(validator.validate(new CompareRequest(2, 2))).isNotEmpty();
	}

	@Test
	@DisplayName("파라미터가 없거나 0 이하면 실패")
	void failsWhenMissingOrNotPositive() {
		assertThat(validator.validate(new CompareRequest(null, 2))).isNotEmpty();
		assertThat(validator.validate(new CompareRequest(1, null))).isNotEmpty();
		assertThat(validator.validate(new CompareRequest(0, 2))).isNotEmpty();
	}
}
