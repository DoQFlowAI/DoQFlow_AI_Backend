package com.rookies6.DoQFlowAI.repository;

import com.rookies6.DoQFlowAI.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 감사 로그 리포지토리 — 담당 홍서연
 *
 * <p>조회 API는 만들지 않는다(이슈 F 범위 밖). 시연과 보안점검서는 DB에서 직접 본다.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}