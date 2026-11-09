package com.rookies6.DoQFlowAI.dto.ai;

/// 업로드 파일의 본문 추출 ( POST /internal/extract )
public final class AiExtract {

	private AiExtract() {
	}

	/**
	 * @param versionId document_version.id
	 * @param filePath  AI Module이 읽을 원본 파일 경로 (두 컨테이너가 같은 업로드 볼륨을 공유)
	 * @param mimeType  application/pdf 또는 DOCX MIME 타입
	 */
	public record Request(
			Long versionId,
			String filePath,
			String mimeType
	) {
	}

	/**
	 * @param contentText 추출한 본문. diff가 줄 단위이므로 문장 단위로 줄바꿈되어 있어야 한다.
	 * @param pageCount   페이지 수
	 */
	public record Response(
			String contentText,
			Integer pageCount
	) {
	}
}
