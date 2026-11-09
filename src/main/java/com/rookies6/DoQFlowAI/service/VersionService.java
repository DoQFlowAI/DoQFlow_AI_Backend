package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.response.VersionContentResponse;
import com.rookies6.DoQFlowAI.dto.response.VersionUploadResponse;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentStatus;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

	private final DocumentVersionRepository versions;
	private final UploadFileValidator validator;
	private final VersionFileStorage storage;
	private final VersionProcessor processor;
	private final int viewMaxBytes;

	public VersionService(
			DocumentVersionRepository versions,
			UploadFileValidator validator,
			VersionFileStorage storage,
			VersionProcessor processor,
			@Value("${app.upload.view-max-bytes:1048576}") int viewMaxBytes) {
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
	 *   version_no는 기존 최대값 + 1부터 보낸 파일 순서대로 붙인다.
	 *   텍스트 계열은 이 트랜잭션 안에서 파일을 UTF-8로 읽어 content_text를 채우고 READY로 둔다 (AI 추출 호출 없음).
	 *   문서 계열은 PROCESSING으로 두고 후처리에서 추출한다.
	 * 후처리(VersionProcessor)는 트랜잭션이 커밋된 뒤에 시작한다. 커밋 전에 시작하면 다른 스레드가 아직 저장되지 않은 행을 못 본다.
	 *
	 * TODO AuditService(홍서연)가 들어오면 VERSION_UPLOAD / 검증 실패 시 VERSION_UPLOAD_FAILED 감사 로그를 남긴다.
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
		deleteFilesOnRollback(documentId, stored);

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
		afterCommit(() -> startProcessing(documentId, previousId, createdIds));

		return VersionUploadResponse.of(document, created);
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

	// 트랜잭션이 있으면 커밋 뒤에 실행하고, 없으면(단위 테스트) 바로 실행한다
	private static void afterCommit(Runnable task) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		} else {
			task.run();
		}
	}

	// 커밋 단계에서 실패해 롤백되면 이미 저장한 파일도 지운다 (DB에 행이 없는 고아 파일 방지)
	private void deleteFilesOnRollback(Long documentId, List<StoredFile> stored) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status == STATUS_ROLLED_BACK) {
						deleteFiles(documentId, stored);
					}
				}
			});
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
