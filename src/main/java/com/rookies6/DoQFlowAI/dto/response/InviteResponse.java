package com.rookies6.DoQFlowAI.dto.response;

import java.util.List;

/**
 * 초대 결과 < API 5.7 >
 *
 * <p>일부 실패로 전체를 막지 않는다. 가입하지 않은 이메일은 notFound로
 * 돌려주고 나머지는 그대로 초대한다.
 *
 * @param invited  실제로 초대가 만들어진 수. 이미 PENDING·ACCEPTED라 건너뛴 건 제외
 * @param notFound 가입되지 않은 이메일
 */
public record InviteResponse(int invited, List<String> notFound) {
}