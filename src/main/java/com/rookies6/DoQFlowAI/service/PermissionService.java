package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.PermissionResponse;
import com.rookies6.DoQFlowAI.repository.DocumentPermissionRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.rookies6.DoQFlowAI.dto.response.InviteResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentPermission;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import java.util.ArrayList;

/** 열람 권한 관리 < API 5.7, 5.8 > */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {

    private final AccessPolicy accessPolicy;
    private final DocumentPermissionRepository permissions;
    private final AppUserRepository users;
    private final AuditService audit;

    /**
     * 열람자 목록 < API 5.7 >
     *
     * <p>권한은 쓰기(그 문서의 등록자)다. 초대받은 사람은 다른 열람자가
     * 누구인지 볼 수 없다. 그래서 문서 단건 조회(D-3)에 넣지 않고 분리했다.
     */
    public List<PermissionResponse> list(Long userId, Long documentId) {
        accessPolicy.writeOrThrow(userId, documentId);

        return permissions.findByDocumentIdWithUser(documentId).stream()
                .map(PermissionResponse::of)
                .toList();
    }

    /**
     * 열람자 추가 초대 < API 5.7 >
     *
     * <p>일부 실패로 전체를 막지 않는다. 가입하지 않은 이메일은 notFound로
     * 돌려주고 나머지는 처리한다.
     *
     * <p>이미 PENDING·ACCEPTED인 사람은 오류가 아니라 조용히 건너뛴다.
     * 화면에서 중복 클릭해도 같은 결과가 나와야 한다.
     */
    @Transactional
    public InviteResponse invite(Long userId, Long documentId, List<String> emails) {
        Document document = accessPolicy.writeOrThrow(userId, documentId);
        AppUser inviter = users.getReferenceById(userId);

        int invited = 0;
        List<String> notFound = new ArrayList<>();

        for (String email : emails) {
            AppUser target = users.findByEmail(email).orElse(null);
            if (target == null) {
                notFound.add(email);
                continue;
            }

            // 등록자는 이미 전체 권한이 있다. 초대 행을 만들면 자기 문서가
            // 자기 초대함에 뜨고, 회수(P-4)에서 막히는 행이 남는다.
            if (accessPolicy.isOwner(document, target.getId())) {
                continue;
            }

            DocumentPermission existing = permissions
                    .findByDocumentIdAndUserId(documentId, target.getId())
                    .orElse(null);

            if (existing == null) {
                permissions.save(DocumentPermission.builder()
                        .document(document)
                        .user(target)
                        .grantedBy(inviter)
                        .build());
            } else if (existing.getStatus() == PermissionStatus.REJECTED) {
                existing.reopen(inviter);
            } else {
                continue;   // PENDING·ACCEPTED — 오류가 아니다
            }

            invited++;
            audit.log(AuditEventType.PERMISSION_GRANT, AuditResult.SUCCESS,
                    userId, "DOCUMENT", documentId,
                    "invited: " + AuditService.maskEmail(email));
        }

        return new InviteResponse(invited, notFound);
    }
}