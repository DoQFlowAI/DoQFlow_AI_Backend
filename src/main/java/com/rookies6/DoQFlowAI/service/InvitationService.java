package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.InvitationResponse;
import com.rookies6.DoQFlowAI.entity.PermissionStatus;
import com.rookies6.DoQFlowAI.repository.DocumentPermissionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 받은 초대 < API 5.9 > */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationService {

    private final DocumentPermissionRepository permissions;

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
}