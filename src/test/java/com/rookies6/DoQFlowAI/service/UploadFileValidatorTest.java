package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rookies6.DoQFlowAI.exception.UploadValidationException;
import com.rookies6.DoQFlowAI.exception.UploadValidationException.Reason;
import com.rookies6.DoQFlowAI.service.UploadFileValidator.ValidatedFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** [API-18 V-01] 업로드 파일 검증 단위 테스트 — Backend 개발 명세서 7의 검증 순서별 확인 */
class UploadFileValidatorTest {

	private final UploadFileValidator validator = new UploadFileValidator(10);

	private static MockMultipartFile pdf(String name, String body) {
		return new MockMultipartFile("files", name, "application/pdf",
				("%PDF-1.7\n" + body).getBytes(StandardCharsets.UTF_8));
	}

	private static MockMultipartFile docx(String name, String body) {
		byte[] head = {'P', 'K', 0x03, 0x04};
		byte[] rest = body.getBytes(StandardCharsets.UTF_8);
		byte[] bytes = new byte[head.length + rest.length];
		System.arraycopy(head, 0, bytes, 0, head.length);
		System.arraycopy(rest, 0, bytes, head.length, rest.length);
		return new MockMultipartFile("files", name, "application/octet-stream", bytes);
	}

	private void assertRejected(List<MultipartFile> files, String previousHash, Reason reason) {
		assertThatThrownBy(() -> validator.validate(files, previousHash))
				.isInstanceOf(UploadValidationException.class)
				.extracting(e -> ((UploadValidationException) e).getReason())
				.isEqualTo(reason);
	}

	@Test
	void PDF와_DOCX를_순서대로_통과시키고_MIME과_해시를_정한다() {
		List<ValidatedFile> result = validator.validate(List.of(pdf("v1.pdf", "a"), docx("v2.DOCX", "b")), null);

		assertThat(result).extracting(ValidatedFile::originalFilename).containsExactly("v1.pdf", "v2.DOCX");
		assertThat(result).extracting(ValidatedFile::extension).containsExactly("pdf", "docx");
		assertThat(result.get(1).mimeType())
				.isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		assertThat(result.get(0).contentHash()).hasSize(64).isNotEqualTo(result.get(1).contentHash());
	}

	@Test
	void 파일이_없거나_10개를_넘으면_FILE_COUNT() {
		assertRejected(List.of(), null, Reason.FILE_COUNT);
		assertRejected(null, null, Reason.FILE_COUNT);

		List<MultipartFile> eleven = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			eleven.add(pdf("v" + i + ".pdf", "body" + i));
		}
		assertRejected(eleven, null, Reason.FILE_COUNT);
	}

	@Test
	void 빈_파일은_EMPTY_FILE() {
		MockMultipartFile empty = new MockMultipartFile("files", "v1.pdf", "application/pdf", new byte[0]);
		assertRejected(List.of(empty), null, Reason.EMPTY_FILE);
	}

	@Test
	void 허용하지_않은_확장자나_위장_파일은_UNSUPPORTED_TYPE() {
		assertRejected(List.of(pdf("v1.txt", "a")), null, Reason.UNSUPPORTED_TYPE);
		assertRejected(List.of(pdf("noextension", "a")), null, Reason.UNSUPPORTED_TYPE);
		// 확장자는 pdf인데 내용은 DOCX(zip)
		assertRejected(List.of(docx("fake.pdf", "a")), null, Reason.UNSUPPORTED_TYPE);
	}

	@Test
	void 파일당_20MB를_넘으면_FILE_TOO_LARGE() {
		byte[] big = new byte[(int) UploadFileValidator.MAX_FILE_SIZE + 1];
		big[0] = '%';
		big[1] = 'P';
		big[2] = 'D';
		big[3] = 'F';
		MockMultipartFile tooLarge = new MockMultipartFile("files", "big.pdf", "application/pdf", big);
		assertRejected(List.of(tooLarge), null, Reason.FILE_TOO_LARGE);
	}

	@Test
	void 경로가_들어간_파일명은_INVALID_FILENAME() {
		assertRejected(List.of(pdf("../../etc/v1.pdf", "a")), null, Reason.INVALID_FILENAME);
		assertRejected(List.of(pdf("..\\v1.pdf", "a")), null, Reason.INVALID_FILENAME);
		assertRejected(List.of(pdf("C:v1.pdf", "a")), null, Reason.INVALID_FILENAME);
		assertRejected(List.of(pdf("v1\0.pdf", "a")), null, Reason.INVALID_FILENAME);
	}

	@Test
	void 직전_버전과_내용이_같으면_SAME_AS_PREVIOUS() {
		String previousHash = validator.validate(List.of(pdf("v1.pdf", "same")), null).get(0).contentHash();

		// 이미 저장된 마지막 버전과 같은 경우
		assertRejected(List.of(pdf("v2.pdf", "same")), previousHash, Reason.SAME_AS_PREVIOUS);
		// 같은 요청 안에서 바로 앞 파일과 같은 경우
		assertRejected(List.of(pdf("v1.pdf", "x"), pdf("v2.pdf", "x")), null, Reason.SAME_AS_PREVIOUS);
	}

	@Test
	void 바로_앞이_아닌_예전_버전과_같은_내용은_허용한다() {
		List<ValidatedFile> result = validator.validate(
				List.of(pdf("v1.pdf", "a"), pdf("v2.pdf", "b"), pdf("v3.pdf", "a")), null);

		assertThat(result).hasSize(3);
	}
}
