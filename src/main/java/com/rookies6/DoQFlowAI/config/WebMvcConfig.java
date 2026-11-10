package com.rookies6.DoQFlowAI.config;

import com.rookies6.DoQFlowAI.security.CurrentUserResolver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 확장 등록.
 *
 * <p>리졸버를 등록하지 않으면 {@code @CurrentUser AppUser}가 조용히 null로 들어온다.
 * 예외가 나지 않아 알아채기 어렵고, 권한 판정에 null userId가 넘어가 전부 404가 된다.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

	private final CurrentUserResolver currentUserResolver;

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(currentUserResolver);
	}
}
