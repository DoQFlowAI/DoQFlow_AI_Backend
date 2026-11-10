package com.rookies6.DoQFlowAI.entity;

/**
 * 감사 로그 이벤트 < v1.5 §2.7 >
 *
 * <p>팀 공용 enum이다. <b>상수 추가만 하고 기존 상수의 이름을 바꾸지 않는다.</b>
 * audit_log.event_type에 name() 문자열로 저장되므로, 이름을 바꾸면 이미 쌓인
 * 행과 매칭되지 않는다. 순서는 저장에 쓰이지 않으니 어디에 넣어도 무방하다.
 */
public enum AuditEventType {

    /** 인증 — AuthService (홍서연) */
    LOGIN_SUCCESS,
    LOGIN_FAILURE,

    /** 권한 거부 — EntryPoint·AccessDeniedHandler (홍서연) */
    UNAUTHORIZED,
    ACCESS_DENIED,

    /** 문서 — DocumentService (손인선) */
    DOCUMENT_CREATE,
    DOCUMENT_UPDATE,
    DOCUMENT_DELETE,

    /** 버전 — VersionService (손인선) */
    VERSION_UPLOAD,
    VERSION_DELETE,

    /** 열람 권한 — PermissionService·InvitationService (홍서연, 이슈 G·H) */
    PERMISSION_GRANT,
    PERMISSION_REVOKE,
    PERMISSION_ACCEPT,
    PERMISSION_REJECT,

    /** 질의응답 — ChatService (정세진). 문서 ID만 남기고 질문 원문은 기록하지 않는다 */
    CHAT_QUESTION
}