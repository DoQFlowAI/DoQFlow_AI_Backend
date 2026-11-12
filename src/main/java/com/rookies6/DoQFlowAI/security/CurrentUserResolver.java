package com.rookies6.DoQFlowAI.security;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.exception.BusinessException;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentUser}가 붙은 AppUser 파라미터를 채운다.
 *
 * <p><b>JwtAuthFilter와의 계약</b>
 * 필터는 Authentication의 name(= principal)에 <b>app_user.public_id(UUID)</b>를 담는다.
 * 내부 PK(id)나 이메일을 담지 않는다. 토큰에 들어가는 subject가 publicId이고,
 * 내부 PK가 토큰·응답으로 새어 나가면 다른 사용자의 id를 추측할 수 있게 된다 (04 §1).
 * 이 리졸버는 그 publicId로 DB를 조회한다.
 *
 * <p><b>왜 매 요청 DB를 보는가</b>
 * 직급·계정 상태를 토큰에서 읽지 않기 때문이다. 직급이 바뀌거나 계정이 잠기면
 * 재로그인 없이 다음 요청부터 적용돼야 한다 < R-2 >
 *
 * <p>토큰이 유효한데 사용자 행이 없으면(삭제된 계정) 404가 아니라 401로 막는다.
 * 404를 쓰면 "토큰은 맞지만 계정이 지워졌다"는 사실이 응답으로 드러난다.
 */
@Component
@RequiredArgsConstructor
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

	private final AppUserRepository users;

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentUser.class)
				&& AppUser.class.isAssignableFrom(parameter.getParameterType());
	}

	@Override
	public Object resolveArgument(MethodParameter parameter,
			ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest,
			WebDataBinderFactory binderFactory) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null
				|| !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "no authentication in context");
		}

		String publicId = authentication.getName();
		return users.findByPublicId(publicId)
				.orElseThrow(() -> new BusinessException(
						ErrorCode.UNAUTHORIZED, "user not found for publicId=" + publicId));
	}
}
