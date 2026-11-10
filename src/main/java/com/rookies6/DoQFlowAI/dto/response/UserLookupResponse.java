package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;

/**
 * 초대 대상 조회 < API 5.1 >
 *
 * <p>이메일을 마스킹하지 않는다. 초대 화면에서 입력한 이메일이 맞는지
 * 보여줘야 대상을 확정할 수 있다. 계정 열거 경로가 되는 것은 인지하고
 * 수용한 결정이며(아키텍처 v1.3), 분당 호출 한도로 완화하고
 * 9번 보안점검서의 잔여 위험에 기록한다.
 */
public record UserLookupResponse(String uuid, String name, String email) {

    public static UserLookupResponse of(AppUser user) {
        return new UserLookupResponse(user.getPublicId(), user.getName(), user.getEmail());
    }
}