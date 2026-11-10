package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rookies6.DoQFlowAI.exception.UploadValidationException;
import com.rookies6.DoQFlowAI.exception.UploadValidationException.Reason;
import com.rookies6.DoQFlowAI.service.UploadFileValidator.ValidatedFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** [API-18 V-01] 업로드 파일 검증 단위 테스트 — Backend 개발 명세서 7의 검증 순서별 확인 */
class UploadFileValidatorTest {

	private static final List<String> TEXT_EXT = List.of("txt", "md", "csv", "json", "xml", "yaml", "yml", "log");
	private static final List<String> DOC_EXT = List.of("pdf", "docx");

	private final UploadFileValidator validator = new UploadFileValidator(10, TEXT_EXT, DOC_EXT);

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

	private static MockMultipartFile text(String name, byte[] bytes) {
		// 브라우저가 보낸 Content-Type은 쓰지 않으므로 일부러 엉뚱한 값을 넣는다.
		return new MockMultipartFile("files", name, "application/octet-stream", bytes);
	}

	private static MockMultipartFile text(String name, String body) {
		return text(name, body.getBytes(StandardCharsets.UTF_8));
	}

	private void assertRejected(List<MultipartFile> files, String previousHash, Reason reason) {
		assertThatThrownBy(() -> validator.validate(files, previousHash))
				.isInstanceOf(UploadValidationException.class)
				.extracting(e -> ((UploadValidationException) e).getReason())
				.isEqualTo(reason);
	}

	@Test
	@DisplayName("PDF와 DOCX를 순서대로 통과시키고 MIME과 해시를 정한다")
	void acceptsPdfAndDocxInOrderWithMimeAndHash() {
		List<ValidatedFile> result = validator.validate(List.of(pdf("v1.pdf", "a"), docx("v2.DOCX", "b")), null);

		assertThat(result).extracting(ValidatedFile::originalFilename).containsExactly("v1.pdf", "v2.DOCX");
		assertThat(result).extracting(ValidatedFile::extension).containsExactly("pdf", "docx");
		assertThat(result).extracting(ValidatedFile::kind).containsExactly(FileKind.DOC, FileKind.DOC);
		assertThat(result.get(1).mimeType())
				.isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
		assertThat(result.get(0).contentHash()).hasSize(64).isNotEqualTo(result.get(1).contentHash());
	}

	@Test
	@DisplayName("텍스트 계열 8종은 UTF-8이면 통과하고 MIME은 확장자로 정한다")
	void acceptsEightTextKindsWhenUtf8() {
		List<MultipartFile> files = new ArrayList<>();
		for (String ext : TEXT_EXT) {
			files.add(text("v." + ext, "1. 목적\r\n\t이 지침은 " + ext + "\n"));
		}

		List<ValidatedFile> result = validator.validate(files, null);

		assertThat(result).extracting(ValidatedFile::kind).containsOnly(FileKind.TEXT);
		assertThat(result).extracting(ValidatedFile::mimeType).containsExactly(
				"text/plain", "text/markdown", "text/csv", "application/json",
				"application/xml", "application/yaml", "application/yaml", "text/plain");
	}

	@Test
	@DisplayName("한 요청에 두 계열이 섞여도 파일마다 판정한다")
	void judgesEachFileInMixedRequest() {
		List<ValidatedFile> result = validator.validate(List.of(pdf("v1.pdf", "a"), text("v2.md", "# b")), null);

		assertThat(result).extracting(ValidatedFile::kind).containsExactly(FileKind.DOC, FileKind.TEXT);
	}

	@Test
	@DisplayName("파일이 없거나 10개를 넘으면 FILE_COUNT")
	void noFilesOrOverTenIsFileCount() {
		assertRejected(List.of(), null, Reason.FILE_COUNT);
		assertRejected(null, null, Reason.FILE_COUNT);

		List<MultipartFile> eleven = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			eleven.add(pdf("v" + i + ".pdf", "body" + i));
		}
		assertRejected(eleven, null, Reason.FILE_COUNT);
	}

	@Test
	@DisplayName("빈 파일도 FILE_COUNT")
	void emptyFileIsFileCount() {
		MockMultipartFile empty = new MockMultipartFile("files", "v1.pdf", "application/pdf", new byte[0]);
		assertRejected(List.of(empty), null, Reason.FILE_COUNT);
		assertRejected(List.of(text("v1.txt", new byte[0])), null, Reason.FILE_COUNT);
	}

	@Test
	@DisplayName("지원하지 않는 확장자나 위장 파일은 INVALID_TYPE")
	void unsupportedOrDisguisedFileIsInvalidType() {
		assertRejected(List.of(pdf("noextension", "a")), null, Reason.INVALID_TYPE);
		// 마크업으로 해석되는 형식은 일부러 받지 않는다 (저장형 XSS 방지)
		assertRejected(List.of(text("v1.html", "<p>a</p>")), null, Reason.INVALID_TYPE);
		assertRejected(List.of(text("v1.svg", "<svg/>")), null, Reason.INVALID_TYPE);
		// 확장자는 pdf인데 내용은 DOCX(zip)
		assertRejected(List.of(docx("fake.pdf", "a")), null, Reason.INVALID_TYPE);
	}

	@Test
	@DisplayName("텍스트 계열이 UTF-8이 아니거나 제어문자가 있으면 INVALID_TYPE")
	void nonUtf8OrControlCharTextIsInvalidType() {
		// 확장자만 txt로 바꾼 PDF 바이너리 (0xE2 0xE3 ...는 올바른 UTF-8이 아님)
		byte[] binary = {'%', 'P', 'D', 'F', '\n', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3};
		assertRejected(List.of(text("v1.txt", binary)), null, Reason.INVALID_TYPE);
		// EUC-KR로 저장한 한글
		assertRejected(List.of(text("v1.txt", "한글".getBytes(java.nio.charset.Charset.forName("EUC-KR")))),
				null, Reason.INVALID_TYPE);
		// 널 바이트, 그 밖의 C0 제어문자
		assertRejected(List.of(text("v1.log", "a\0b")), null, Reason.INVALID_TYPE);
		assertRejected(List.of(text("v1.csv", "a\u0007b")), null, Reason.INVALID_TYPE);
	}

	@Test
	@DisplayName("설정에서 뺀 확장자는 받지 않는다")
	void rejectsExtensionRemovedFromConfig() {
		UploadFileValidator docOnly = new UploadFileValidator(10, List.of(), DOC_EXT);

		assertThatThrownBy(() -> docOnly.validate(List.of(text("v1.txt", "a")), null))
				.extracting(e -> ((UploadValidationException) e).getReason())
				.isEqualTo(Reason.INVALID_TYPE);
	}

	@Test
	@DisplayName("파일당 20MB를 넘으면 FILE_TOO_LARGE")
	void over20MbIsFileTooLarge() {
		byte[] big = new byte[(int) UploadFileValidator.MAX_FILE_SIZE + 1];
		big[0] = '%';
		big[1] = 'P';
		big[2] = 'D';
		big[3] = 'F';
		MockMultipartFile tooLarge = new MockMultipartFile("files", "big.pdf", "application/pdf", big);
		assertRejected(List.of(tooLarge), null, Reason.FILE_TOO_LARGE);
	}

	@Test
	@DisplayName("경로가 들어간 파일명은 INVALID_FILENAME")
	void filenameWithPathIsInvalidFilename() {
		assertRejected(List.of(pdf("../../etc/v1.pdf", "a")), null, Reason.INVALID_FILENAME);
		assertRejected(List.of(pdf("..\\v1.pdf", "a")), null, Reason.INVALID_FILENAME);
		assertRejected(List.of(pdf("C:v1.pdf", "a")), null, Reason.INVALID_FILENAME);
		assertRejected(List.of(pdf("v1\0.pdf", "a")), null, Reason.INVALID_FILENAME);
	}

	@Test
	@DisplayName("직전 버전과 내용이 같으면 DUPLICATE_CONTENT")
	void sameAsPreviousIsDuplicateContent() {
		String previousHash = validator.validate(List.of(pdf("v1.pdf", "same")), null).get(0).contentHash();

		// 이미 저장된 마지막 버전과 같은 경우
		assertRejected(List.of(pdf("v2.pdf", "same")), previousHash, Reason.DUPLICATE_CONTENT);
		// 같은 요청 안에서 바로 앞 파일과 같은 경우
		assertRejected(List.of(pdf("v1.pdf", "x"), pdf("v2.pdf", "x")), null, Reason.DUPLICATE_CONTENT);
	}

	@Test
	@DisplayName("바로 앞이 아닌 예전 버전과 같은 내용은 허용한다")
	void allowsContentSameAsOlderVersion() {
		List<ValidatedFile> result = validator.validate(
				List.of(pdf("v1.pdf", "a"), pdf("v2.pdf", "b"), pdf("v3.pdf", "a")), null);

		assertThat(result).hasSize(3);
	}
}
