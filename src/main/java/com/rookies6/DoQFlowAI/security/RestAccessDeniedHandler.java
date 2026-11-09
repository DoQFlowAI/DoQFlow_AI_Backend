package com.rookies6.DoQFlowAI.security;

import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 인증은 됐지만 접근이 거부된 경우 403을 만든다. /internal/** 외부 접근이 여기로 온다.
 *
 * <p>EntryPoint와 같은 이유로 응답을 직접 쓰지 않고 HandlerExceptionResolver에 넘긴다.
 *
 * <p>문서 열람 권한은 여기서 판정하지 않는다. 그쪽은 AccessPolicy가 404로 막는다.
 * 못 읽는 문서에 403이 나가면 응답 코드만으로 문서의 존재가 드러난다 < SEC-03 >
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

	private final HandlerExceptionResolver resolver;

	/**
	 * 생성자를 직접 쓴다. Lombok의 @RequiredArgsConstructor는 lombok.config에
	 * copyableAnnotations를 지정하지 않으면 필드의 @Qualifier를 생성자 파라미터로
	 * 복사하지 않는다. 그러면 HandlerExceptionResolver 빈이 여러 개라 주입이 깨진다.
	 */
	public RestAccessDeniedHandler(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public void handle(HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException accessDeniedException) {

		resolver.resolveException(request, response, null,
				new ForbiddenException(ErrorCode.FORBIDDEN.name()));
	}
}
