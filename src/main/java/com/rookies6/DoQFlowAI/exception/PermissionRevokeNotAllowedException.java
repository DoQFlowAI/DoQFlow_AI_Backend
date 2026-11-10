package com.rookies6.DoQFlowAI.exception;

/**
 * 회수할 수 없는 대상 — 400 VALIDATION_ERROR < API 5.8 >
 *
 * <p>등록자 본인의 권한은 회수할 수 없다. 회수하면 그 문서를 관리할 수 있는
 * 사람이 아무도 없게 된다.
 */
public class PermissionRevokeNotAllowedException extends BusinessException {

    public PermissionRevokeNotAllowedException(String detail) {
        super(ErrorCode.VALIDATION_ERROR, detail);
    }
}