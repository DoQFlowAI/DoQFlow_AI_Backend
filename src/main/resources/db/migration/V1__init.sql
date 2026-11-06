-- DoQFlow AI 초기 스키마 (Backend 개발 명세서 4.1 기준)
-- 시각은 DATETIME, enum 값은 VARCHAR로 저장한다.
-- 사용자 FK는 RESTRICT, 문서·대화 하위 데이터는 CASCADE, 답변 근거의 문서·버전 참조는 SET NULL이다.

CREATE TABLE app_user (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    public_id          CHAR(36)     NOT NULL,
    email              VARCHAR(255) NOT NULL,
    password_hash      CHAR(60)     NOT NULL,
    name               VARCHAR(50)  NOT NULL,
    rank_level         INT          NOT NULL,
    failed_login_count INT          NOT NULL DEFAULT 0,
    locked_until       DATETIME     NULL,
    created_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_public_id UNIQUE (public_id),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_rank_level CHECK (rank_level BETWEEN 1 AND 2)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE refresh_token (
    id         BIGINT     NOT NULL AUTO_INCREMENT,
    user_id    BIGINT     NOT NULL,
    token_hash CHAR(64)   NOT NULL,
    expires_at DATETIME   NOT NULL,
    revoked    TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    INDEX idx_refresh_token_user_revoked (user_id, revoked)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE document (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    name               VARCHAR(200) NOT NULL,
    description        VARCHAR(500) NULL,
    uploaded_by        BIGINT       NOT NULL,
    current_version_no INT          NOT NULL DEFAULT 0,
    status             VARCHAR(12)  NOT NULL DEFAULT 'EMPTY',
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_document_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_document_status CHECK (status IN ('EMPTY', 'PROCESSING', 'READY', 'FAILED')),
    INDEX idx_document_uploaded_by (uploaded_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE document_permission (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    document_id  BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    granted_by   BIGINT      NOT NULL,
    status       VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    granted_at   DATETIME    NOT NULL,
    responded_at DATETIME    NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_document_permission_document_user UNIQUE (document_id, user_id),
    CONSTRAINT fk_document_permission_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT fk_document_permission_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_permission_granted_by FOREIGN KEY (granted_by) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_document_permission_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    INDEX idx_document_permission_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE document_version (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    document_id       BIGINT       NOT NULL,
    version_no        INT          NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename   VARCHAR(100) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    file_size         BIGINT       NOT NULL,
    content_hash      CHAR(64)     NOT NULL,
    content_text      LONGTEXT     NULL,
    status            VARCHAR(12)  NOT NULL DEFAULT 'PROCESSING',
    change_type       VARCHAR(12)  NULL,
    change_summary    TEXT         NULL,
    change_count      INT          NULL,
    summary_status    VARCHAR(10)  NULL,
    diff_json         JSON         NULL,
    created_at        DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_document_version_document_version_no UNIQUE (document_id, version_no),
    CONSTRAINT uk_document_version_stored_filename UNIQUE (stored_filename),
    CONSTRAINT fk_document_version_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT ck_document_version_status CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_document_version_summary_status CHECK (summary_status IN ('OK', 'FAILED', 'SKIPPED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE chat_session (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(200) NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    INDEX idx_chat_session_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id BIGINT      NOT NULL,
    role       VARCHAR(10) NOT NULL,
    content    TEXT        NOT NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_session FOREIGN KEY (session_id) REFERENCES chat_session (id) ON DELETE CASCADE,
    CONSTRAINT ck_chat_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    INDEX idx_chat_message_session_created (session_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE message_source (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    message_id    BIGINT       NOT NULL,
    document_id   BIGINT       NULL,
    version_id    BIGINT       NULL,
    document_name VARCHAR(200) NOT NULL,
    version_no    INT          NOT NULL,
    page_number   INT          NULL,
    chunk_text    TEXT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_message_source_message FOREIGN KEY (message_id) REFERENCES chat_message (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_source_document FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE SET NULL,
    CONSTRAINT fk_message_source_version FOREIGN KEY (version_id) REFERENCES document_version (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE audit_log (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT      NULL,
    event_type    VARCHAR(40) NOT NULL,
    target_type   VARCHAR(20) NULL,
    target_id     BIGINT      NULL,
    result        VARCHAR(10) NOT NULL,
    ip_address    VARCHAR(45) NULL,
    trace_id      VARCHAR(36) NULL,
    detail_json   JSON        NULL,
    created_at    DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_log_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    INDEX idx_audit_log_event_created (event_type, created_at),
    INDEX idx_audit_log_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
