package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.VersionContentResponse;
import com.rookies6.DoQFlowAI.dto.response.VersionResponse;
import com.rookies6.DoQFlowAI.dto.response.VersionUploadResponse;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.exception.DeleteNotAllowedException;
import com.rookies6.DoQFlowAI.exception.DocumentNotFoundException;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.service.UploadFileValidator.ValidatedFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 버전 서비스 (Backend 개발 명세서 4.3 VersionService)
 *
 * 권한 확인은 컨트롤러에서 AccessPolicy로 먼저 하고(읽기 readOrNotFound, 쓰기 writeOrThrow),
 * 이 클래스는 확인이 끝난 문서를 받아 처리한다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class VersionService {

	private final DocumentRepository documents;
	private final DocumentVersionRepository versions;
	private final UploadFileValidator validator;
	private final VersionFileStorage storage;
	private final VersionProcessor processor;
	private final int viewMaxBytes;

	public VersionService(
			DocumentRepository documents,
			DocumentVersionRepository versions,
			UploadFileValidator validator,
			VersionFileStorage storage,
			VersionProcessor processor,
			@Value("${app.upload.view-max-bytes:1048576}") int viewMaxBytes) {
		this.documents = documents;
		this.versions = versions;
		this.validator = validator;
		this.storage = storage;
		this.processor = processor;
		this.viewMaxBytes = viewMaxBytes;
	}

	/**
	 * 버전 일괄 업로드 — 동기 부분 < API 18 >
	 *
	 * 검증 → 볼륨 저장 → 행 생성 → 201. 하나라도 실패하면 전체를 거부하고 아무것도 남기지 않는다.
	 * 후처리(VersionProcessor)는 트랜잭션이 커밋된 뒤에 시작한다. 커밋 전에 시작하면 다른 스레드가 아직 저장되지 않은 행을 못 본다.
	 *
	 * @param document AccessPolicy.writeOrThrow()를 통과한 문서 (등록자 본인)
	 * @param files    요청의 files (배열 순서 = 버전 순서)
	 * @throws com.rookies6.DoQFlowAI.exception.UploadValidationException 검증 실패 (400, 20MB 초과는 413)
	 */
	@Transactional
	public VersionUploadResponse uploadBatch(Document document, List<MultipartFile> files) {
		Long documentId = document.getId();
		DocumentVersion previous = versions.findTopByDocumentIdOrderByVersionNoDesc(documentId).orElse(null);
		List<ValidatedFile> validated = validator.validate(files, previous == null ? null : previous.getContentHash());

		int versionNo = versions.findMaxVersionNo(documentId);
		List<StoredFile> stored = new ArrayList<>();
		TransactionHooks.onRollback(() -> deleteFiles(documentId, stored)); // 커밋 단계 실패 대비

		List<DocumentVersion> created = new ArrayList<>();
		try {
			for (ValidatedFile file : validated) {
				versionNo++;
				String storedFilename = storage.store(documentId, versionNo, file);
				stored.add(new StoredFile(versionNo, storedFilename));

				DocumentVersion version = DocumentVersion.builder()
						.document(document)
						.versionNo(versionNo)
						.originalFilename(file.originalFilename())
						.storedFilename(storedFilename)
						.mimeType(file.mimeType())
						.fileSize(file.size())
						.contentHash(file.contentHash())
						.build();
				if (file.kind() == FileKind.TEXT) {
					version.completeExtraction(readUtf8(file.file())); // 줄바꿈 그대로
				}
				created.add(versions.save(version));
			}
			versions.flush(); // UNIQUE(document_id, version_no) 위반 등을 여기서 드러내 파일 정리 대상에 넣는다
		} catch (RuntimeException e) {
			deleteFiles(documentId, stored);
			throw e;
		}

		document.changeCurrentVersion(versionNo);
		document.changeStatus(DocumentStatus.PROCESSING);

		Long previousId = previous == null ? null : previous.getId();
		List<Long> createdIds = created.stream().map(DocumentVersion::getId).toList();
		TransactionHooks.afterCommit(() -> startProcessing(documentId, previousId, createdIds));

		return VersionUploadResponse.of(document, created);
	}

	/**
	 * 버전 목록 < API 19 >
	 * 버전 번호 오름차순. 버전이 없으면 빈 목록이다.
	 *
	 * @param document AccessPolicy.readOrNotFound()를 통과한 문서
	 */
	public List<VersionResponse> list(Document document) {
		return versions.findByDocumentIdOrderByVersionNo(document.getId()).stream()
				.map(VersionResponse::from)
				.toList();
	}

	/**
	 * 버전 단건 < API 20 >
	 *
	 * @param document AccessPolicy.readOrNotFound()를 통과한 문서
	 * @throws VersionNotFoundException 없는 버전 (404 DOCUMENT_NOT_FOUND, 못 읽는 문서와 같은 응답)
	 */
	public VersionResponse get(Document document, int versionNo) {
		return versions.findByDocumentIdAndVersionNo(document.getId(), versionNo)
				.map(VersionResponse::from)
				.orElseThrow(VersionNotFoundException::new);
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

	/**
	 * 버전 삭제 < API 21 > (API 설계서 5.13)
	 *
	 * 행을 지우고 current_version_no를 남은 최대값으로 다시 맞춘다.
	 *   중간 버전을 지우면 뒤 버전의 변경점을 비우고, 앞·뒤 버전 사이 구간을 후처리에서 다시 만든다.
	 *   최신 버전을 지우면 직전 버전이 최신이 되므로 문서를 PROCESSING으로 두고 후처리에서 재색인한다.
	 *   맨 앞 버전을 지우면 다음 버전이 v1 역할이 되어 변경점을 비운다 (v1의 변경점은 null).
	 * message_source.version_id는 DB에서 SET NULL이 되어, 이 버전을 근거로 한 과거 답변은 대화 기록에서 가려진다.
	 * 저장 파일은 커밋이 끝난 뒤에 지운다.
	 *
	 * @param documentId AccessPolicy.writeOrThrow()를 통과한 문서 (등록자 본인)
	 * @throws VersionNotFoundException   없는 버전 (404)
	 * @throws DeleteNotAllowedException 버전이 하나뿐이거나 업로드 후처리 중 (400)
	 */
	@Transactional
	public void deleteVersion(Long documentId, int versionNo) {
		Document document = documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
		if (document.getStatus() == DocumentStatus.PROCESSING) {
			throw new DeleteNotAllowedException("document is processing: documentId=" + documentId);
		}
		List<DocumentVersion> all = versions.findByDocumentIdOrderByVersionNo(documentId);
		int index = indexOf(all, versionNo);
		if (index < 0) {
			throw new VersionNotFoundException();
		}
		if (all.size() == 1) {
			throw new DeleteNotAllowedException("only one version left: documentId=" + documentId);
		}

		DocumentVersion target = all.get(index);
		DocumentVersion before = index > 0 ? all.get(index - 1) : null;
		DocumentVersion after = index < all.size() - 1 ? all.get(index + 1) : null;
		versions.delete(target);

		boolean latestDeleted = after == null;
		if (after != null) {
			after.clearChange(); // 지운 버전 → after 구간은 더 이상 없다
		}
		if (latestDeleted) {
			document.changeCurrentVersion(before.getVersionNo());
			document.changeStatus(DocumentStatus.PROCESSING); // 재색인이 끝나면 READY
		}

		Long fromId = before != null && after != null ? before.getId() : null;
		Long toId = before != null && after != null ? after.getId() : null;
		Long reindexId = latestDeleted ? before.getId() : null;
		int deletedNo = target.getVersionNo();
		String deletedFile = target.getStoredFilename();
		TransactionHooks.afterCommit(() -> {
			deleteFiles(documentId, List.of(new StoredFile(deletedNo, deletedFile)));
			if (fromId != null || reindexId != null) {
				startRebuild(documentId, fromId, toId, reindexId);
			}
		});
	}

	private static int indexOf(List<DocumentVersion> all, int versionNo) {
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).getVersionNo() == versionNo) {
				return i;
			}
		}
		return -1;
	}

	private void startRebuild(Long documentId, Long fromId, Long toId, Long reindexId) {
		try {
			processor.rebuildAfterDelete(documentId, fromId, toId, reindexId);
		} catch (TaskRejectedException e) {
			log.warn("post-processing queue is full: documentId={}", documentId);
			if (reindexId != null) {
				processor.markFailed(documentId);
			}
		}
	}

	private void startProcessing(Long documentId, Long previousId, List<Long> createdIds) {
		try {
			processor.process(documentId, previousId, createdIds);
		} catch (TaskRejectedException e) {
			// 후처리 대기열(50개)까지 찼다. PROCESSING에 멈춰 있지 않게 FAILED로 둔다
			log.warn("post-processing queue is full: documentId={}", documentId);
			processor.markFailed(documentId);
		}
	}

	private static String readUtf8(MultipartFile file) {
		try {
			return new String(file.getBytes(), StandardCharsets.UTF_8); // 검증에서 올바른 UTF-8임을 확인했다
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void deleteFiles(Long documentId, List<StoredFile> stored) {
		for (StoredFile file : stored) {
			try {
				storage.delete(documentId, file.versionNo(), file.storedFilename());
			} catch (RuntimeException e) {
				log.warn("failed to remove stored file: documentId={}, versionNo={}", documentId, file.versionNo());
			}
		}
	}

	private record StoredFile(int versionNo, String storedFilename) {
	}
}
