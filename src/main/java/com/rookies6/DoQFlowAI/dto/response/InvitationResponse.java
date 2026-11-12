package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import java.time.LocalDateTime;

/**
 * 받은 초대 한 건 < API 5.9 >
 *
 * <p>문서 이름과 초대한 사람 이름만 내린다. 아직 수락하지 않았으므로
 * documentId나 문서 내용은 주지 않는다. 받으면 그 id로 바로 조회를 시도할 수 있다.
 */
public record InvitationResponse(
        Long invitationId,
        String documentName,
        String invitedByName,
        LocalDateTime grantedAt
) {

    public static InvitationResponse of(DocumentPermission permission) {
        return new InvitationResponse(
                permission.getId(),
                permission.getDocument().getName(),
                permission.getGrantedBy().getName(),
                permission.getGrantedAt());
    }
}