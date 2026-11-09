package com.rookies6.DoQFlowAI.exception;

import com.rookies6.DoQFlowAI.dto.response.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 오류 응답을 한 곳에서 만든다 < BE 8 >
 *
 * 규칙 세 개를 지킨다.
 * 1. 사용자에게 가는 문구는 ErrorCode의 고정 문구뿐이다. 예외 메시지를 응답에 싣지 않는다.
 * 2. 상세는 traceId로 서버 로그에서만 본다. 스택 트레이스·SQL·파일 경로는 응답에 넣지 않는다.
 * 3. 없는 리소스와 권한 밖 리소스는 같은 404·같은 code·같은 문구다 < SEC-03 >
 *
 * 새 도메인 예외는 BusinessException만 상속하면 여기를 고치지 않아도 잡힌다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final String TRACE_ID = "traceId";

	/** 업무 예외 전체. 도메인 예외는 모두 여기로 들어온다 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		return build(e.getErrorCode(), e);
	}

	/**
	 * 버전 업로드 파일 검증 < API 5.9 >
	 * UploadValidationException은 Reason에 상태·코드를 직접 들고 있어 그 값을 따른다.
	 */
	@ExceptionHandler(UploadValidationException.class)
	public ResponseEntity<ErrorResponse> handleUploadValidation(UploadValidationException e) {
		ErrorCode code = ErrorCode.of(e.getReason().code());
		log.warn("[{}] upload validation failed reason={} code={}",
				traceId(), e.getReason().name(), code.name());
		return ResponseEntity.status(e.getReason().status())
				.body(ErrorResponse.of(code, traceId()));
	}

	/** @Valid 본문 검증 실패. 어느 필드가 문제인지는 알려줘도 된다 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e) {
		FieldError first = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
		String message = first == null
				? ErrorCode.VALIDATION_ERROR.message()
				: first.getField() + ": " + first.getDefaultMessage();
		log.warn("[{}] body validation failed {}", traceId(), message);
		return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.status())
				.body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, message, traceId()));
	}

	/**
	 * 쿼리 파라미터·경로 변수 검증 실패, 타입 불일치, 깨진 JSON
	 *
	 * HandlerMethodValidationException을 빼먹으면 안 된다.
	 * @Min·@Max를 컨트롤러 파라미터에 직접 붙이면 ConstraintViolationException이 아니라
	 * 이쪽이 올라오고, ResponseStatusException을 상속해서 catch-all로 흘러 500이 된다.
	 * 목록 API의 size 상한(50) 검증이 전부 여기 걸린다 < API 2.3 >
	 */
	@ExceptionHandler({
			ConstraintViolationException.class,
			HandlerMethodValidationException.class,
			MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class,
			HttpMessageNotReadableException.class
	})
	public ResponseEntity<ErrorResponse> handleRequestValidation(Exception e) {
		return build(ErrorCode.VALIDATION_ERROR, e);
	}

	/** 멀티파트 전체 크기 초과. 파일당 20MB 검증은 UploadFileValidator가 따로 본다 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException e) {
		return build(ErrorCode.FILE_TOO_LARGE, e);
	}

	/**
	 * 메서드 보안에서 막힌 경우. 필터 단계에서 막히는 401·403은
	 * SecurityConfig의 EntryPoint·AccessDeniedHandler가 같은 규격으로 내려준다.
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
		return build(ErrorCode.FORBIDDEN, e);
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException e) {
		return build(ErrorCode.UNAUTHORIZED, e);
	}

	/** 없는 경로. 이게 없으면 아래 catch-all이 500으로 바꿔버린다 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
		return build(ErrorCode.NOT_FOUND, e);
	}

	/** 그 외 전부 500. 원인은 로그에만 남는다 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		return build(ErrorCode.INTERNAL_ERROR, e);
	}

	/**
	 * 5xx는 스택 트레이스까지, 4xx는 한 줄만 남긴다.
	 * 4xx는 호출하는 쪽의 잘못이라 스택을 남겨도 쓸모가 없고 로그만 지저분해진다.
	 */
	private ResponseEntity<ErrorResponse> build(ErrorCode code, Exception e) {
		if (code.status() >= 500) {
			log.error("[{}] {} {}", traceId(), code.name(), e.getMessage(), e);
		} else {
			log.warn("[{}] {} {}", traceId(), code.name(), e.getMessage());
		}
		return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, traceId()));
	}

	private String traceId() {
		String id = MDC.get(TRACE_ID);
		return id == null ? "" : id;
	}
}
