package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동 시 끊긴 업로드 후처리 복구 < API 18, API 21 >
 *
 * 후처리(VersionProcessor: 추출·diff·요약·색인)는 Backend 안의 스레드풀에서 돈다. 배포·재시작으로 프로세스가 끝나면
 * 진행 중이던 문서·버전이 PROCESSING에 남는다. PROCESSING 문서는 삭제도 막혀 있어(400) 화면에서 정리할 수 없다.
 *
 * 앱이 막 떴을 때는 진행 중인 후처리가 있을 수 없으므로, 남아 있는 PROCESSING은 모두 끊긴 작업이다.
 * 그래서 기동 직후 FAILED로 바꾼다. 사용자는 실패로 보고 삭제하거나 새 버전을 다시 올리면 된다.
 *   버전 PROCESSING → FAILED : 추출이 끝나지 않아 본문이 없다 (비교에서 빠진다)
 *   문서 PROCESSING → FAILED : 색인이 끝나지 않아 검색에서 빠진다
 *
 * 전제 — Backend가 한 대다 (Compose). 여러 대로 늘리면 새로 뜬 쪽이 다른 쪽에서 진행 중인 작업까지 FAILED로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingRecovery {

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void recover() {
		int failedVersions = versions.failAllProcessing();
		int failedDocuments = documents.failAllProcessing(LocalDateTime.now());
		if (failedVersions > 0 || failedDocuments > 0) {
			log.warn("interrupted post-processing recovered: documents={} versions={} -> FAILED",
					failedDocuments, failedVersions);
		}
	}
}
