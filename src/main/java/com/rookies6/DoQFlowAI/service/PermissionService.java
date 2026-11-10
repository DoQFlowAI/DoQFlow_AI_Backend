package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.PermissionResponse;
import com.rookies6.DoQFlowAI.repository.DocumentPermissionRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 열람 권한 관리 < API 5.7, 5.8 > */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionService {

    private final AccessPolicy accessPolicy;
    private final DocumentPermissionRepository permissions;

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
}