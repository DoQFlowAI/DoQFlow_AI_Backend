package com.rookies6.DoQFlowAI.dto.response;

import com.rookies6.DoQFlowAI.service.DiffBlock;
import java.util.List;

/**
 * 두 버전 비교 응답 (GET, /documents/{documentId}/compare?from=&to=) < API 24 >
 */
public record CompareResponse(
		int fromVersionNo,
		int toVersionNo,
		String fromText,
		String toText,
		int changeCount,
		List<DiffBlock> diffJson
) {
}
