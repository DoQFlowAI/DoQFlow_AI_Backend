package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.config.AsyncConfig;
import com.rookies6.DoQFlowAI.dto.ai.AiExtract;
import com.rookies6.DoQFlowAI.dto.ai.AiHistory;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.SummaryStatus;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 버전 업로드 후처리 < API 18 >
 *
 * 업로드 트랜잭션이 커밋된 뒤 전용 스레드풀(uploadExecutor)에서 돈다. 트랜잭션 밖에서 단계마다 저장한다.
 * VersionService와 다른 bean으로 둔 이유: 같은 클래스 안에서 부르면 @Async가 적용되지 않는다.
 *
 * 순서
 *   1. 추출   — 문서 계열(pdf, docx)만 /internal/extract. 텍스트 계열은 업로드 때 본문을 이미 채웠다
 *   2. diff   — 인접 구간(직전 버전 → 새 첫 버전, 새 버전끼리)마다 DiffService로 계산해 diff_json·change_count 저장
 *   3. 요약   — 변경 블록이 없으면 SKIPPED(LLM 미호출), 있으면 구간 하나씩 /internal/history
 *   4. 색인   — 마지막 버전만 /internal/index, 성공하면 DELETE /internal/index(keep=마지막)로 이전 벡터 정리
 *   5. 문서 상태 READY (색인 실패·마지막 버전 추출 실패면 FAILED)
 *
 * 실패 처리
 *   추출 실패 → 그 버전만 FAILED, 나머지 계속
 *   요약 실패 → 그 구간만 summary_status FAILED, 계속 (재시도는 AI Module이 한다)
 *   색인 실패 → 문서 FAILED (검색 제외)
 *   이전 벡터 삭제 실패 → 로그만 남김
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VersionProcessor {

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;
	private final AiClientService aiClient;
	private final DiffService diffService;
	private final VersionFileStorage storage;

	/**
	 * @param documentId        문서 ID
	 * @param previousVersionId 이번 업로드 직전의 마지막 버전 ID (첫 업로드면 null). 직전 버전 → 새 첫 버전 구간을 만들 때 쓴다
	 * @param newVersionIds     이번에 올린 버전 ID들
	 */
	@Async(AsyncConfig.UPLOAD_EXECUTOR)
	public void process(Long documentId, Long previousVersionId, List<Long> newVersionIds) {
		try {
			run(documentId, previousVersionId, newVersionIds);
		} catch (RuntimeException e) {
			// 예상하지 못한 오류로 PROCESSING에 멈춰 있지 않게 한다
			log.error("version post-processing failed: documentId={}", documentId, e);
			markDocument(documentId, DocumentStatus.FAILED);
		}
	}

	/** 후처리를 시작하지 못했을 때(스레드풀 대기열 초과 등) 문서를 FAILED로 둔다 */
	public void markFailed(Long documentId) {
		markDocument(documentId, DocumentStatus.FAILED);
	}

	/**
	 * 버전 삭제 뒤 정리 < API 21 >
	 *
	 * 끊어진 구간 재생성 — 지운 버전의 앞(from)과 뒤(to)를 새 인접 구간으로 보고 diff·요약을 다시 만든다.
	 * 재색인 — 최신 버전을 지웠으면 새 최신 버전(직전 버전)을 색인하고 그 외 벡터를 지운 뒤 문서를 READY로 둔다.
	 *
	 * @param fromVersionId   새 구간의 앞 버전 (지운 버전이 맨 앞이었거나 맨 뒤였으면 null)
	 * @param toVersionId     새 구간의 뒤 버전 (지운 버전이 맨 뒤였으면 null)
	 * @param reindexVersionId 다시 색인할 버전 (최신 버전을 지웠을 때만, 아니면 null)
	 */
	@Async(AsyncConfig.UPLOAD_EXECUTOR)
	public void rebuildAfterDelete(Long documentId, Long fromVersionId, Long toVersionId, Long reindexVersionId) {
		try {
			if (fromVersionId != null && toVersionId != null) {
				DocumentVersion from = versions.findById(fromVersionId).orElse(null);
				DocumentVersion to = versions.findById(toVersionId).orElse(null);
				if (from != null && to != null) {
					summarizeSegment(documentId, from, to);
				}
			}
			if (reindexVersionId != null) {
				DocumentVersion latest = versions.findById(reindexVersionId).orElse(null);
				boolean indexed = latest != null && index(documentId, latest);
				markDocument(documentId, indexed ? DocumentStatus.READY : DocumentStatus.FAILED);
			}
		} catch (RuntimeException e) {
			log.error("rebuild after version delete failed: documentId={}", documentId, e);
			if (reindexVersionId != null) {
				markDocument(documentId, DocumentStatus.FAILED); // PROCESSING에 멈춰 있지 않게
			}
		}
	}

	void run(Long documentId, Long previousVersionId, List<Long> newVersionIds) {
		List<DocumentVersion> created = new ArrayList<>(versions.findAllById(newVersionIds));
		created.sort(Comparator.comparingInt(DocumentVersion::getVersionNo));
		if (created.isEmpty()) {
			return;
		}

		// 1. 추출 (문서 계열만)
		for (DocumentVersion version : created) {
			if (version.getStatus() == VersionStatus.PROCESSING) {
				extract(documentId, version);
			}
		}

		// 2·3. 인접 구간 diff와 요약
		List<DocumentVersion> chain = new ArrayList<>();
		if (previousVersionId != null) {
			versions.findById(previousVersionId).ifPresent(chain::add);
		}
		chain.addAll(created);
		for (int i = 1; i < chain.size(); i++) {
			summarizeSegment(documentId, chain.get(i - 1), chain.get(i));
		}

		// 4·5. 마지막 버전 색인과 문서 상태
		DocumentVersion last = created.get(created.size() - 1);
		markDocument(documentId, index(documentId, last) ? DocumentStatus.READY : DocumentStatus.FAILED);
	}

	private void extract(Long documentId, DocumentVersion version) {
		String path = storage.relativePath(documentId, version.getVersionNo(), version.getStoredFilename());
		try {
			AiExtract.Response response = aiClient.extract(version.getId(), path, version.getMimeType());
			if (response.contentText() == null || response.contentText().isBlank()) {
				version.failExtraction(); // 스캔 이미지 PDF처럼 본문이 비면 비교·검색할 수 없다
			} else {
				version.completeExtraction(response.contentText());
			}
		} catch (AiModuleException e) {
			version.failExtraction();
		}
		versions.save(version);
	}

	/**
	 * 버전 n의 행에 n-1 → n 구간의 변경점을 저장한다
	 * 둘 중 하나라도 본문이 없으면(FAILED) 구간을 만들지 않는다. change_count가 비어 변천사에서 빠진다.
	 */
	private void summarizeSegment(Long documentId, DocumentVersion from, DocumentVersion to) {
		if (from.getStatus() != VersionStatus.READY || to.getStatus() != VersionStatus.READY) {
			return;
		}
		List<DiffBlock> blocks = diffService.diff(from.getContentText(), to.getContentText());
		to.updateDiff(diffService.toJson(blocks), blocks.size());

		if (blocks.isEmpty()) {
			to.updateSummary(SummaryStatus.SKIPPED, null, null);
		} else {
			applySummary(to, requestSummary(documentId, from, to, blocks));
		}
		versions.save(to);
	}

	private AiHistory.Segment requestSummary(Long documentId, DocumentVersion from, DocumentVersion to,
			List<DiffBlock> blocks) {
		List<String> fromLines = DiffService.lines(from.getContentText());
		List<String> toLines = DiffService.lines(to.getContentText());
		AiHistory.RequestSegment segment = new AiHistory.RequestSegment(
				from.getVersionNo(),
				to.getVersionNo(),
				blocks.stream().map(block -> AiHistory.Block.of(block, fromLines, toLines)).toList());
		try {
			// 구간 하나씩 보낸다. 읽기 제한(180초)이 구간 단위로 걸리고, 실패해도 이 구간만 FAILED가 된다
			return aiClient.history(documentId, List.of(segment)).segments().stream()
					.filter(s -> s.from() == from.getVersionNo() && s.to() == to.getVersionNo())
					.findFirst()
					.orElse(null);
		} catch (AiModuleException e) {
			return null;
		}
	}

	private static void applySummary(DocumentVersion to, AiHistory.Segment result) {
		if (result != null && "OK".equals(result.summaryStatus())) {
			to.updateSummary(SummaryStatus.OK, result.changeType(), result.summary());
		} else {
			to.updateSummary(SummaryStatus.FAILED, null, null);
		}
	}

	/**
	 * 마지막 버전만 색인하고, 성공하면 같은 문서의 이전 벡터를 지운다.
	 * AI Module 재시작 등으로 추출 단계의 임시 파일이 없어 색인이 실패하면,
	 * 문서 계열은 추출부터 한 번 다시 한다 (AI 모듈 연동 명세서 4.2).
	 *
	 * TODO 텍스트 계열은 추출을 거치지 않아 AI Module에 색인용 임시 파일이 없다.
	 *      /internal/index 요청에 filePath·mimeType을 넣는 방안을 AI 모듈 담당과 협의 중이다.
	 *
	 * @return 색인 성공 여부
	 */
	private boolean index(Long documentId, DocumentVersion last) {
		if (last.getStatus() != VersionStatus.READY) {
			return false;
		}
		if (!tryIndex(documentId, last)) {
			boolean canReExtract = FileKind.fromMimeType(last.getMimeType()) == FileKind.DOC;
			if (!canReExtract || !reExtract(documentId, last) || !tryIndex(documentId, last)) {
				return false;
			}
		}
		try {
			aiClient.deleteIndex(documentId, last.getId());
		} catch (AiModuleException e) {
			log.warn("old vectors were not removed: documentId={}", documentId);
		}
		return true;
	}

	private boolean tryIndex(Long documentId, DocumentVersion last) {
		try {
			aiClient.index(documentId, last.getId(), last.getVersionNo());
			return true;
		} catch (AiModuleException e) {
			return false;
		}
	}

	// 색인용 임시 파일을 다시 만들기 위한 추출. 본문은 이미 저장돼 있으므로 바꾸지 않는다.
	private boolean reExtract(Long documentId, DocumentVersion version) {
		String path = storage.relativePath(documentId, version.getVersionNo(), version.getStoredFilename());
		try {
			aiClient.extract(version.getId(), path, version.getMimeType());
			return true;
		} catch (AiModuleException e) {
			return false;
		}
	}

	private void markDocument(Long documentId, DocumentStatus status) {
		documents.findById(documentId).ifPresent(document -> {
			document.changeStatus(status);
			documents.save(document);
		});
	}
}
