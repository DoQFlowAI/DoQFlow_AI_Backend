package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.config.TraceIdFilter;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 감사 로그 < v1.5 §2.7 >
 *
 * <p><b>팀 공용 진입점이다. 시그니처를 바꾸면 세 사람 코드가 같이 깨진다.</b>
 *
 * <p>기록하지 않는 것 — 비밀번호, 토큰 원문, 질문 원문. 이메일은 maskEmail로
 * 가린 뒤 넘긴다.
 *
 * <p>이 메서드는 예외를 던지지 않는다. 감사 기록이 실패했다고 로그인이나
 * 업로드 같은 본 작업이 500이 되면 안 된다. 실패는 서버 로그에만 남긴다.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    // @Slf4j를 쓰지 않는다. 생성되는 필드 이름(log)이 아래 log() 메서드와 겹쳐
    // 읽기 어려워진다.
    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditWriter writer;

    /**
     * 감사 로그 한 줄을 남긴다.
     *
     * @param eventType   이벤트 종류
     * @param result      SUCCESS / FAILURE
     * @param actorUserId 행위자. 특정할 수 없으면 null (미가입 이메일 로그인, 401 등)
     * @param targetType  대상 종류. "DOCUMENT", "VERSION", "APP_USER" 등. 없으면 null
     * @param targetId    대상 PK. 없으면 null
     * @param detail      사람이 읽을 한 줄. 원문 비밀번호·토큰·질문을 넣지 않는다
     */
    public void log(AuditEventType eventType, AuditResult result, Long actorUserId,
                    String targetType, Long targetId, String detail) {

        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        try {
            writer.write(eventType, result, actorUserId, targetType, targetId,
                    currentIp(), traceId, toJson(detail));
        } catch (Exception e) {
            LOG.error("감사 로그 저장 실패: eventType={}, traceId={}", eventType, traceId, e);
        }
    }

    /** ho***@example.com — 호출자가 detail에 넣기 전에 이걸로 가린다 */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String head = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return head + "***" + email.substring(at);
    }

    /**
     * detail_json은 MariaDB의 JSON 컬럼이고 json_valid() CHECK가 자동으로 붙어 있다.
     * 평문 문자열을 그대로 넣으면 INSERT가 ERROR 4025로 거부되므로 항상 감싼다.
     */
    private static String toJson(String detail) {
        String cleaned = clean(detail);
        if (cleaned == null || cleaned.isBlank()) {
            return null;        // NULL은 CHECK를 통과한다
        }
        return "{\"detail\":\"" + escape(cleaned) + "\"}";
    }

    /**
     * 제어문자를 모두 공백으로 바꾼다.
     *
     * <p>두 가지를 동시에 막는다. 줄바꿈이 남으면 로그에 가짜 줄을 심을 수 있고
     * (로그 인젝션), JSON 문자열 안에 날 제어문자가 들어가면 json_valid()가 실패한다.
     * detail에는 요청 URI처럼 사용자가 정하는 값이 섞이므로 반드시 거친다.
     */
    private static String clean(String raw) {
        return raw == null ? null : raw.replaceAll("\\p{Cntrl}", " ");
    }

    /** 역슬래시를 먼저 치환해야 한다. 순서를 바꾸면 이스케이프가 중복된다 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 요청 IP.
     *
     * <p><b>X-Forwarded-For는 읽지 않는다.</b> 클라이언트가 직접 보내는 헤더라
     * 아무 값이나 넣을 수 있고, 앞단에 신뢰할 수 있는 프록시도 없다.
     * TraceIdFilter가 X-Trace-Id를 받지 않는 것과 같은 이유다.
     */
    private static String currentIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return null;        // 요청 바깥(스케줄러·테스트)에서 부른 경우
        }
        return servletAttrs.getRequest().getRemoteAddr();
    }
}