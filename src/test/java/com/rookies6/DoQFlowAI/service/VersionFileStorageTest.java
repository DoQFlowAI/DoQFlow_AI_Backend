package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.service.UploadFileValidator.ValidatedFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apiguardian.api.API;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/// 업로드 파일 저장·삭제 단위 테스트 <API 18>
class VersionFileStorageTest {

	@TempDir
	Path uploadDir;

	private ValidatedFile validated(String name, String body) {
		MockMultipartFile file = new MockMultipartFile("files", name, "application/pdf",
				("%PDF-1.7\n" + body).getBytes(StandardCharsets.UTF_8));
		return new UploadFileValidator(10, List.of("txt"), List.of("pdf", "docx")).validate(List.of(file), null).get(0);
	}

	@Test
	void 문서와_버전별_폴더에_UUID_파일명으로_저장한다() throws Exception {
		VersionFileStorage storage = new VersionFileStorage(uploadDir.toString());
		ValidatedFile file = validated("원본이름.pdf", "본문");

		String storedFilename = storage.store(7L, 2, file);

		assertThat(storedFilename).matches("[0-9a-f-]{36}\\.pdf");
		Path saved = uploadDir.resolve("7").resolve("2").resolve(storedFilename);
		assertThat(saved).exists();
		assertThat(Files.readAllBytes(saved)).isEqualTo(file.file().getBytes());
	}

	@Test
	void 저장한_파일을_삭제하고_없는_파일_삭제는_무시한다() {
		VersionFileStorage storage = new VersionFileStorage(uploadDir.toString());
		String storedFilename = storage.store(7L, 1, validated("v1.pdf", "a"));

		storage.delete(7L, 1, storedFilename);
		storage.delete(7L, 1, storedFilename);

		assertThat(uploadDir.resolve("7").resolve("1").resolve(storedFilename)).doesNotExist();
	}

	@Test
	void AI_Module에_넘길_경로는_업로드_디렉터리_기준_상대_경로다() {
		VersionFileStorage storage = new VersionFileStorage(uploadDir.toString());

		assertThat(storage.relativePath(7L, 3, "5f1c.pdf")).isEqualTo("7/3/5f1c.pdf");
	}

	@Test
	void 저장_파일명으로_업로드_디렉터리_밖을_가리킬_수_없다() {
		VersionFileStorage storage = new VersionFileStorage(uploadDir.toString());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> storage.relativePath(7L, 3, "../../../etc/passwd"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
