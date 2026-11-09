package com.rookies6.DoQFlowAI.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/** 두 버전 비교 요청 검증 단위 테스트 < API 24 > — 위반이 있으면 컨트롤러에서 400이 된다. */
class CompareRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 서로_다른_두_버전이면_통과() {
		assertThat(validator.validate(new CompareRequest(1, 4))).isEmpty();
		assertThat(validator.validate(new CompareRequest(4, 1))).isEmpty();
	}

	@Test
	void 같은_버전이면_실패() {
		assertThat(validator.validate(new CompareRequest(2, 2))).isNotEmpty();
	}

	@Test
	void 파라미터가_없거나_0_이하면_실패() {
		assertThat(validator.validate(new CompareRequest(null, 2))).isNotEmpty();
		assertThat(validator.validate(new CompareRequest(1, null))).isNotEmpty();
		assertThat(validator.validate(new CompareRequest(0, 2))).isNotEmpty();
	}
}
