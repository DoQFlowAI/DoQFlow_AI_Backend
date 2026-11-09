package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 두 버전 비교 요청 (GET, /documents/{documentId}/compare?from=&to=) [ from, to ] < API 24 >
 *
 * from, to가 없거나 같은 버전이면 400 (VALIDATION_ERROR).
 * 순서는 정하지 않는다. from이 to보다 커도 비교한다.
 * 컨트롤러에서 {@code @Valid @ModelAttribute}로 받는다.
 */
public record CompareRequest(

		@NotNull
		@Min(1)
		Integer from,

		@NotNull
		@Min(1)
		Integer to
) {

	// from과 to가 같으면 검증 실패(400). 값이 없을 때는 @NotNull이 먼저 막는다.
	@AssertTrue
	public boolean isDifferentVersions() {
		return from == null || to == null || !from.equals(to);
	}
}
