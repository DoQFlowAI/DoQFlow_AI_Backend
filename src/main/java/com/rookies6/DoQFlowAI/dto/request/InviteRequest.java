package com.rookies6.DoQFlowAI.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 열람자 추가 초대 < API 5.7 >
 *
 * <p>상한 50명은 명세 §5.2의 문서 생성 시 초대와 같은 값이다. 두 경로 모두
 * PermissionService.invite 하나를 타므로 제약도 같아야 한다.
 *
 * <p>여러 명을 한 번에 받는다. 리스트 원소에 건 제약(@Email·@NotBlank)은
 * 컨테이너 요소 제약이라 @Valid @RequestBody 경로에서 그대로 검증된다.
 */
public record InviteRequest(

        @NotEmpty(message = "초대할 이메일을 한 명 이상 입력하세요.")
        @Size(max = 50, message = "한 번에 최대 50명까지 초대할 수 있습니다.")
        List<@NotBlank @Email @Size(max = 255) String> inviteeEmails
) {
}