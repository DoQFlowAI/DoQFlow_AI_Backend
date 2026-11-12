package com.rookies6.DoQFlowAI.security;

import com.rookies6.DoQFlowAI.exception.BusinessException;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.service.AuditService;

/**
 * 인증 없이 보호된 경로에 들어왔을 때 401을 만든다.
 *
 * <p>필터 단계에서 막히는 요청은 컨트롤러에 닿지 않아 @RestControllerAdvice가 잡지 못한다.
 * 그래서 EntryPoint가 따로 필요하다.
 *
 * <p><b>응답을 직접 쓰지 않고 HandlerExceptionResolver에 넘긴다.</b> 이유가 두 가지다.
 * 하나는 오류 봉투를 GlobalExceptionHandler 한 곳에서만 만들게 해서 형식이 갈라지지 않게 하는 것이고,
 * 다른 하나는 여기서 ObjectMapper로 JSON을 직접 쓰지 않아도 되는 것이다.
 * Boot 4는 Jackson 3를 쓰므로 ObjectMapper의 패키지가 달라질 수 있는데 그 문제를 아예 피한다.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final HandlerExceptionResolver resolver;
	private final AuditService audit;

	/**
	 * 생성자를 직접 쓴다. Lombok의 @RequiredArgsConstructor는 lombok.config에
	 * copyableAnnotations를 지정하지 않으면 필드의 @Qualifier를 생성자 파라미터로
	 * 복사하지 않는다. 그러면 HandlerExceptionResolver 빈이 여러 개라 주입이 깨진다.
	 */
	public RestAuthenticationEntryPoint(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
			AuditService audit) {
		this.resolver = resolver;
		this.audit = audit;
	}

	@Override
	public void commence(HttpServletRequest request,
						 HttpServletResponse response,
						 AuthenticationException authException) {

		// 토큰이 없거나 깨진 요청이라 행위자를 특정할 수 없다.
		// requestURI는 사용자가 정하는 값이지만 AuditService.clean이 제어문자를 지운다.
		audit.log(AuditEventType.UNAUTHORIZED, AuditResult.FAILURE,
				null, null, null, request.getMethod() + " " + request.getRequestURI());

		resolver.resolveException(request, response, null,
				new BusinessException(ErrorCode.UNAUTHORIZED, authException.getMessage()));
	}
}
