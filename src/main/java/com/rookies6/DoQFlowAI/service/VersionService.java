package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.VersionContentResponse;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 버전 서비스 (Backend 개발 명세서 4.3 VersionService)
 *
 * 읽기 권한 확인은 컨트롤러에서 AccessPolicy.readOrNotFound()로 먼저 하고,
 * 이 클래스는 확인이 끝난 문서를 받아 처리한다.
 * 업로드(uploadBatch, processAsync)는 #3에서 이 클래스에 추가한다.
 */
@Service
@Transactional(readOnly = true)
public class VersionService {

	private final DocumentVersionRepository versions;
	private final int viewMaxBytes;

	public VersionService(
			DocumentVersionRepository versions,
			@Value("${app.upload.view-max-bytes:1048576}") int viewMaxBytes) {
		this.versions = versions;
		this.viewMaxBytes = viewMaxBytes;
	}

	/**
	 * 원문 보기 < API 23 >
	 *
	 * 디스크의 파일을 읽지 않고 DB의 content_text만 돌려준다. 요청에 파일 경로가 끼지 않으므로 경로 조작이 성립하지 않는다.
	 * 추출 전(PROCESSING)·추출 실패(FAILED)는 오류가 아니라 content: null로 응답한다.
	 *
	 * @throws VersionNotFoundException 없는 버전 (404 DOCUMENT_NOT_FOUND, 못 읽는 문서와 같은 응답)
	 */
	public VersionContentResponse getContent(Document document, int versionNo) {
		return versions.findByDocumentIdAndVersionNo(document.getId(), versionNo)
				.map(version -> VersionContentResponse.of(version, viewMaxBytes))
				.orElseThrow(VersionNotFoundException::new);
	}
}
