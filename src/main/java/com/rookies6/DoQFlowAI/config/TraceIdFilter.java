package com.rookies6.DoQFlowAI.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 traceId를 MDC에 심는다 < BE 8 >
 * 오류 응답의 traceId와 서버 로그의 traceId가 같아야 로그를 따라갈 수 있다.
 *
 * 값은 항상 서버에서 만든다. 클라이언트가 보낸 X-Trace-Id를 받아 쓰면
 * 로그에 원하는 문자열을 심을 수 있게 되어(로그 인젝션) 받지 않는다.
 *
 * 필터 체인 제일 앞에 둔다. 인증 필터에서 터지는 401도 traceId가 찍혀야 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String TRACE_ID = "traceId";

	private static final String RESPONSE_HEADER = "X-Trace-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String traceId = UUID.randomUUID().toString();
		MDC.put(TRACE_ID, traceId);
		response.setHeader(RESPONSE_HEADER, traceId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(TRACE_ID);
		}
	}
}
