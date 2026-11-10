package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import java.time.LocalDateTime;

/**
 * 열람자 한 명 < API 5.7 >
 *
 * <p>이메일을 마스킹하지 않는다. 등록자가 누구를 초대했는지 확인해야 하고,
 * 이 응답은 그 문서의 등록자에게만 나간다.
 *
 * <p>내부 PK(AppUser.id)는 내보내지 않는다. 회수(P-4)도 uuid로 받는다.
 */
public record PermissionResponse(
        Long permissionId,
        String uuid,
        String name,
        String email,
        PermissionStatus status,
        LocalDateTime grantedAt,
        LocalDateTime respondedAt
) {

    public static PermissionResponse of(DocumentPermission permission) {
        AppUser user = permission.getUser();
        return new PermissionResponse(
                permission.getId(),
                user.getPublicId(),
                user.getName(),
                user.getEmail(),
                permission.getStatus(),
                permission.getGrantedAt(),
                permission.getRespondedAt());
    }
}