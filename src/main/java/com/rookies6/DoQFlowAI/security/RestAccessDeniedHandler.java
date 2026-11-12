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
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.AuditEventType;
import com.rookies6.DoQFlowAI.entity.AuditResult;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import com.rookies6.DoQFlowAI.service.AuditService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
	private final AuditService audit;
	private final AppUserRepository users;

	/**
	 * 생성자를 직접 쓴다. Lombok의 @RequiredArgsConstructor는 lombok.config에
	 * copyableAnnotations를 지정하지 않으면 필드의 @Qualifier를 생성자 파라미터로
	 * 복사하지 않는다. 그러면 HandlerExceptionResolver 빈이 여러 개라 주입이 깨진다.
	 */
	public RestAccessDeniedHandler(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
			AuditService audit,
			AppUserRepository users) {
		this.resolver = resolver;
		this.audit = audit;
		this.users = users;
	}

	@Override
	public void handle(HttpServletRequest request,
					   HttpServletResponse response,
					   AccessDeniedException accessDeniedException) {

		audit.log(AuditEventType.ACCESS_DENIED, AuditResult.FAILURE,
				currentUserId(), null, null,
				request.getMethod() + " " + request.getRequestURI());

		resolver.resolveException(request, response, null,
				new ForbiddenException(ErrorCode.FORBIDDEN.name()));
	}

	/**
	 * JwtAuthFilter가 principal에 publicId를 넣는다(CurrentUserResolver와 같은 약속).
	 * 감사 로그에는 내부 PK가 필요하므로 한 번 조회한다. 403은 드물게 나므로
	 * 조회 비용은 문제되지 않는다. 알 수 없으면 null을 남긴다.
	 */
	private Long currentUserId() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
			return null;
		}
		return users.findByPublicId(auth.getName()).map(AppUser::getId).orElse(null);
	}
}
