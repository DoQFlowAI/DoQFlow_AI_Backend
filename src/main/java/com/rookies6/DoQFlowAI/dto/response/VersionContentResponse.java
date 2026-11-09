package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.service.FileKind;
import com.rookies6.DoQFlowAI.service.Utf8Truncator;

/**
 * 원문 보기 응답 (GET, /documents/{documentId}/versions/{no}/content) < API 23 >
 *
 * kind     TEXT: 파일 원문 그대로 / DOC: 추출한 본문 (원문과 다를 수 있음, 화면에 배지 표시)
 * content  DB의 content_text 그대로. 줄 번호가 비교(API 24) diffJson의 줄 번호와 같다.
 *          PROCESSING(추출 전)·FAILED(추출 실패)면 null
 * truncated 상한(1MB)에서 잘렸으면 true
 *
 * storedFilename·저장 경로는 넣지 않는다. 디스크를 읽지 않으므로 요청으로 경로를 조작할 수 없다.
 */
public record VersionContentResponse(
		int versionNo,
		String originalFilename,
		FileKind kind,
		String content,
		boolean truncated
) {

	public static VersionContentResponse of(DocumentVersion version, int maxBytes) {
		String text = version.getStatus() == VersionStatus.READY ? version.getContentText() : null;
		Utf8Truncator.Result body = Utf8Truncator.truncate(text, maxBytes);
		return new VersionContentResponse(
				version.getVersionNo(),
				version.getOriginalFilename(),
				FileKind.fromMimeType(version.getMimeType()),
				body.content(),
				body.truncated());
	}
}
