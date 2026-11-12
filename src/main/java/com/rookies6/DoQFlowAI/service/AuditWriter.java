package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditLog;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import com.rookies6.DoQFlowAI.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그를 별도 트랜잭션으로 저장한다. AuditService만 부른다.
 *
 * <p><b>AuditService와 클래스를 나눈 이유가 두 가지다.</b>
 *
 * <p>1. REQUIRES_NEW는 스프링 프록시를 거쳐야 적용된다. 같은 클래스 안에서
 * 부르면 자기 호출이라 프록시를 타지 않고 전파 속성이 통째로 무시된다.
 *
 * <p>2. 트랜잭션은 이 메서드가 프록시를 통해 <b>반환될 때</b> 커밋된다.
 * 커밋 시점에 터지는 예외(제약 위반 등)는 메서드 본문 안의 try-catch로는
 * 잡히지 않는다. 호출자인 AuditService가 바깥에서 감싸야 잡을 수 있다.
 */
@Service
@RequiredArgsConstructor
public class AuditWriter {

    private final AuditLogRepository auditLogs;
    private final AppUserRepository users;

    /**
     * REQUIRES_NEW 인 이유: 로그인 실패처럼 예외를 던지며 끝나는 경로에서도
     * 기록이 남아야 한다. 호출자 트랜잭션에 참여하면 롤백되며 같이 사라진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditEventType eventType, AuditResult result, Long actorUserId,
                      String targetType, Long targetId,
                      String ipAddress, String traceId, String detailJson) {

        auditLogs.save(AuditLog.builder()
                // 연관관계라 AppUser가 필요하다. getReferenceById는 지연 프록시만
                // 만들고 SELECT를 날리지 않는다. FK에는 id만 들어간다.
                .actorUser(actorUserId == null ? null : users.getReferenceById(actorUserId))
                .eventType(eventType.name())
                .result(result)
                .targetType(targetType)
                .targetId(targetId)
                .ipAddress(ipAddress)
                .traceId(traceId)
                .detailJson(detailJson)
                .build());
    }
}