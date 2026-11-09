package com.rookies6.DoQFlowAI.exception;

/**
 * 없는 버전 — 404 DOCUMENT_NOT_FOUND
 */
public class VersionNotFoundException extends RuntimeException {

	public VersionNotFoundException() {
		super("version not found");
	}
}
