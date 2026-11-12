package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;


// 문서 이름/설명 수정 요청 (PATCH, /documents/{documentId}) [ name, description ]  <API 10>
public record DocumentUpdateRequest(

		@Size(max = 200)
		@Pattern(regexp = "(?s).*\\S.*")
		String name,

		@Size(max = 500)
		String description
) {

	// name과 description이 모두 없으면 검증 실패(400)
	@AssertTrue
	public boolean isAnyFieldPresent() {
		return name != null || description != null;
	}
}
