package com.rookies6.DoQFlowAI.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 인증된 사용자를 컨트롤러 파라미터로 주입받는다.
 *
 * <pre>
 * {@code
 * @GetMapping("/documents/{id}")
 * public ApiResponse<DocumentDetailResponse> detail(
 *         @CurrentUser AppUser me,
 *         @PathVariable Long id) {
 *     Document document = accessPolicy.readOrNotFound(me.getId(), id);
 *     ...
 * }
 * }
 * </pre>
 *
 * <p>컨트롤러마다 토큰을 파싱하거나 SecurityContext를 직접 열지 않게 하려는 것이다.
 * 파싱이 여러 곳에 흩어지면 한 곳만 빠뜨려도 인증 우회가 생긴다.
 *
 * <p>주입되는 시점에 이미 인증이 끝나 있다. 인증이 없으면 리졸버가 401로 막으므로
 * 컨트롤러에서 null 검사를 할 필요가 없다.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
