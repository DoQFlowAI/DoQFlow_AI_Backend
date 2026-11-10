package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 열람자 추가 초대 < API 5.7 >
 *
 * <p>여러 명을 한 번에 받는다. 리스트 원소에 건 제약(@Email·@NotBlank)은
 * 컨테이너 요소 제약이라 @Valid @RequestBody 경로에서 그대로 검증된다.
 */
public record InviteRequest(

        @NotEmpty(message = "초대할 이메일을 한 명 이상 입력하세요.")
        @Size(max = 20, message = "한 번에 최대 20명까지 초대할 수 있습니다.")
        List<@NotBlank @Email String> inviteeEmails
) {
}