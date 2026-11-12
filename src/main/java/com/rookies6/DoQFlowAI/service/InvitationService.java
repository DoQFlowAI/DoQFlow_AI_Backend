package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.InvitationResponse;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import com.rookies6.DoQFlowAI.repository.DocumentPermissionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.exception.InvitationAlreadyRespondedException;

/** 받은 초대 < API 5.9 > */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationService {

    private final DocumentPermissionRepository permissions;
    private final AuditService audit;

    /**
     * 받은 초대 목록 < API 5.9 > — 본인의 PENDING만
     *
     * <p>ACCEPTED는 이미 문서 목록(D-2)에 나오고, REJECTED는 보여줄 이유가 없다.
     */
    public List<InvitationResponse> list(Long userId) {
        return permissions
                .findByUserIdAndStatusWithDocument(userId, PermissionStatus.PENDING)
                .stream()
                .map(InvitationResponse::of)
                .toList();
    }

    /** 초대 수락 < API 5.9 > */
    @Transactional
    public void accept(Long userId, Long permissionId) {
        respond(userId, permissionId, PermissionStatus.ACCEPTED);
    }

    /** 초대 거절 < API 5.9 > */
    @Transactional
    public void reject(Long userId, Long permissionId) {
        respond(userId, permissionId, PermissionStatus.REJECTED);
    }

    /**
     * 수락·거절 공통 흐름.
     *
     * <p>private이지만 같은 트랜잭션 안에서 돈다. accept·reject가 프록시를
     * 거쳐 들어오므로 여기서는 트랜잭션이 이미 열려 있다.
     */
    private void respond(Long userId, Long permissionId, PermissionStatus next) {
        DocumentPermission permission = permissions.findById(permissionId)
                .orElseThrow(DocumentNotFoundException::new);

        // 남의 초대도 없는 초대와 같은 404다. 403을 쓰면 그 번호의 초대가
        // 존재한다는 사실이 드러나 id를 훑어볼 수 있다 < SEC-03 >
        if (!permission.getUser().getId().equals(userId)) {
            throw new DocumentNotFoundException();
        }

        if (permission.getStatus() != PermissionStatus.PENDING) {
            throw new InvitationAlreadyRespondedException(
                    "permissionId=" + permissionId + ", status=" + permission.getStatus());
        }

        permission.respond(next);

        audit.log(next == PermissionStatus.ACCEPTED
                        ? AuditEventType.PERMISSION_ACCEPT
                        : AuditEventType.PERMISSION_REJECT,
                AuditResult.SUCCESS, userId, "DOCUMENT",
                permission.getDocument().getId(), "invitation " + next);
    }
}