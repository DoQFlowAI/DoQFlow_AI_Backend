package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;


// 문서 생성 / 초대 요청 (POST, /documents) [ name, description, inviteeEmails ] <API 7>
public record DocumentCreateRequest(

		@NotBlank
		@Size(max = 200)
		String name,

		@Size(max = 500)
		String description,

		@Size(max = 50)
		List<@NotBlank @Email @Size(max = 255) String> inviteeEmails
) {

	// 중복 초대 처리
	public List<String> distinctInviteeEmails() {
		if (inviteeEmails == null) {
			return List.of();
		}
		return inviteeEmails.stream()
				.map(email -> email.toLowerCase(Locale.ROOT))
				.distinct()
				.toList();
	}
}
