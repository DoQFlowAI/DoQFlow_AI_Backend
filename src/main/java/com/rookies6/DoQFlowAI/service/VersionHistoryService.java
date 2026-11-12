package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.CompareResponse;
import com.rookies6.DoQFlowAI.dto.response.HistoryResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.exception.VersionNotReadyException;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 변천사·두 버전 비교 < API 22, API 24 >
 *
 * 컨트롤러는 AppUser를 받는 메서드만 부른다. 읽기 권한(AccessPolicy.readOrNotFound)을 먼저 확인해
 * 없는 문서와 권한 밖 문서는 모두 404다. LLM은 호출하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VersionHistoryService {

	private final DocumentVersionRepository versions;
	private final DiffService diffService;
	private final AccessPolicy accessPolicy;

	/** 변천사 타임라인 < API 22 > - 읽기 권한 */
	public HistoryResponse history(AppUser me, Long documentId) {
		return history(accessPolicy.readOrNotFound(me.getId(), documentId));
	}

	/** 두 버전 비교 < API 24 > - 읽기 권한 */
	public CompareResponse compare(AppUser me, Long documentId, int fromNo, int toNo) {
		return compare(accessPolicy.readOrNotFound(me.getId(), documentId), fromNo, toNo);
	}

	public HistoryResponse history(Document document) {
		List<DocumentVersion> all = versions.findByDocumentIdOrderByVersionNo(document.getId());

		List<HistoryResponse.VersionItem> versionItems = all.stream()
				.map(HistoryResponse.VersionItem::from)
				.toList();

		List<HistoryResponse.Segment> segments = new ArrayList<>();
		for (int i = 1; i < all.size(); i++) {
			DocumentVersion previous = all.get(i - 1);
			DocumentVersion current = all.get(i);
			if (current.getChangeCount() == null) {
				continue;
			}
			segments.add(new HistoryResponse.Segment(
					previous.getVersionNo(),
					current.getVersionNo(),
					current.getChangeType(),
					current.getChangeSummary(),
					current.getChangeCount(),
					current.getSummaryStatus() == null ? null : current.getSummaryStatus().name()));
		}
		return new HistoryResponse(document.getId(), versionItems, segments);
	}

	public CompareResponse compare(Document document, int fromNo, int toNo) {
		DocumentVersion from = findVersion(document, fromNo);
		DocumentVersion to = findVersion(document, toNo);
		requireReady(from);
		requireReady(to);

		List<DiffBlock> blocks = isAdjacentWithStoredDiff(document, fromNo, to)
				? diffService.fromJson(to.getDiffJson())
				: diffService.diff(from.getContentText(), to.getContentText());

		return new CompareResponse(
				fromNo,
				toNo,
				textOf(from),
				textOf(to),
				blocks.size(),
				blocks);
	}

	private DocumentVersion findVersion(Document document, int versionNo) {
		return versions.findByDocumentIdAndVersionNo(document.getId(), versionNo)
				.orElseThrow(VersionNotFoundException::new);
	}

	// <API 24> FAILED는 비교 대상에서 제외하고(DB 설계서 4.3), 본문이 아직 없는 PROCESSING도 400으로 거부한다.
	private static void requireReady(DocumentVersion version) {
		if (version.getStatus() != VersionStatus.READY) {
			throw new VersionNotReadyException();
		}
	}

	// from이 to의 실제 직전 버전이고, to에 저장된 diff가 있으면 재사용할 수 있다.
	private boolean isAdjacentWithStoredDiff(Document document, int fromNo, DocumentVersion to) {
		if (fromNo >= to.getVersionNo() || to.getDiffJson() == null) {
			return false;
		}
		Integer previousNo = versions.findPreviousVersionNo(document.getId(), to.getVersionNo());
		return previousNo != null && previousNo == fromNo;
	}

	private static String textOf(DocumentVersion version) {
		return version.getContentText() == null ? "" : version.getContentText();
	}
}
