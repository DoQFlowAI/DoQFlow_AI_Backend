package com.rookies6.DoQFlowAI.exception;


public class AiModuleException extends RuntimeException {

	public AiModuleException(String message) {
		super(message);
	}

	public AiModuleException(String message, Throwable cause) {
		super(message, cause);
	}
}
