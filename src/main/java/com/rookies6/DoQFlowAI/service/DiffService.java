package com.rookies6.DoQFlowAI.service;

import com.github.difflib.DiffUtils;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 버전 관리 영역 < API 18, API 21, API 22, API 23 >
 * LLM이 호출되지 않는 순수 계산 영역
 * 본문은 추출 단계에서 문장 단위로 줄바꿈되어 저장되므로 줄 단위로 비교
 */
@Service
public class DiffService {

	private static final TypeReference<List<DiffBlock>> BLOCK_LIST = new TypeReference<>() {
	};

	private final JsonMapper jsonMapper;

	public DiffService(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/** 두 본문의 변경 블록 계산
	 * 변경 블록 개수 = document_version.change_count
	 * 결과가 비어 있으면 요약 단계에서 summary_status = SKIPPED로 두고 LLM을 호출하지 않는다.
	 *
	 * @param fromText 이전 버전 본문 (null이면 빈 본문으로 본다)
	 * @param toText   다음 버전 본문 (null이면 빈 본문으로 본다)
	 * @return EQUAL을 뺀 변경 블록 목록
	 */
	public List<DiffBlock> diff(String fromText, String toText) {
		List<String> from = toLines(fromText);
		List<String> to = toLines(toText);
		return DiffUtils.diff(from, to).getDeltas().stream()
				.map(delta -> new DiffBlock(
						delta.getType().name(),
						delta.getSource().getPosition(), delta.getSource().size(),
						delta.getTarget().getPosition(), delta.getTarget().size()))
				.toList();
	}

	// 변경 블록을 document_version.diff_json에 저장할 JSON 문자열로 변환
	public String toJson(List<DiffBlock> blocks) {
		return jsonMapper.writeValueAsString(blocks);
	}

	public List<DiffBlock> fromJson(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		return jsonMapper.readValue(json, BLOCK_LIST);
	}

	private static List<String> toLines(String text) {
		return text == null ? List.of() : text.lines().toList();
	}
}
