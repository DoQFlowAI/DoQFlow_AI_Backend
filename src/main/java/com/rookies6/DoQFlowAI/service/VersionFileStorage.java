package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.service.UploadFileValidator.ValidatedFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 업로드한 버전 원본 파일의 저장과 삭제 < API 11, API 18 >
 *
 * <p>저장 경로는 {@code {upload-dir}/{documentId}/{versionNo}/{UUID}.{ext}}이다.
 * 사용자가 보낸 파일명은 경로에 쓰지 않고, 응답에도 경로를 넣지 않는다. 원문 다운로드 API는 없다.
 */
@Component
public class VersionFileStorage {

	private final Path baseDir;

	public VersionFileStorage(@Value("${app.upload-dir}") String uploadDir) {
		this.baseDir = Path.of(uploadDir).toAbsolutePath().normalize();
	}

	/** 파일 저장 및 저장 파일명 반환 < API 18 >
	 *
	 * @param documentId 문서 ID
	 * @param versionNo  이 파일에 부여한 버전 번호
	 * @param file       UploadFileValidator를 통과한 파일
	 * @return {@code {UUID}.{ext}} 형태의 저장 파일명
	 */
	public String store(Long documentId, int versionNo, ValidatedFile file) {
		String storedFilename = UUID.randomUUID() + "." + file.extension();
		Path target = resolve(documentId, versionNo, storedFilename);
		try {
			Files.createDirectories(target.getParent());
			try (InputStream in = file.file().getInputStream()) {
				Files.copy(in, target);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return storedFilename;
	}

	/** 저장한 파일 삭제 < API 18 >
	 *
	 * 업로드 도중 실패하면 이미 저장한 파일을 지워 아무것도 남기지 않을 때 쓴다.
	 * 문서·버전 삭제에서도 재사용한다. 파일이 없으면 아무 일도 하지 않는다.
	 */
	public void delete(Long documentId, int versionNo, String storedFilename) {
		Path target = resolve(documentId, versionNo, storedFilename);
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Path resolve(Long documentId, int versionNo, String storedFilename) {
		Path target = baseDir
				.resolve(String.valueOf(documentId))
				.resolve(String.valueOf(versionNo))
				.resolve(storedFilename)
				.normalize();
		if (!target.startsWith(baseDir)) {
			throw new IllegalArgumentException("upload path escapes the upload directory");
		}
		return target;
	}
}
