package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.exception.UploadValidationException;
import com.rookies6.DoQFlowAI.exception.UploadValidationException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 버전 일괄 업로드 파일 검증 <API 18>
 *
 * 명세의 검증 순서를 그대로 따른다. 1단계 등록자 확인(AccessPolicy.writeOrThrow)은
 * VersionService에서 이 검증보다 먼저 호출한다.
 *
 * (VersionService) 등록자 아니면 403
 * 파일 개수 1~10, 빈 파일 거부 → 400
 * 확장자로 계열 판정 → 400
 *   문서 계열(pdf docx): 매직 넘버 대조 (PDF {@code %PDF}, DOCX {@code PK\x03\x04})
 *   텍스트 계열(그 외 8종): 전체를 UTF-8로 디코딩, 널 바이트와 \t·\r·\n 외 C0 제어문자 거부
 *   브라우저가 보낸 Content-Type은 신뢰하지 않는다.
 * 파일당 20MB → 413
 * 파일명에 경로 구분자, 절대 경로, 널 바이트 → 400
 * 직전 버전과 content_hash(SHA-256) 동일 → 400
 *
 * 이 클래스는 파일을 저장하지 않으므로, 실패 시 아무것도 남지 않는다.
 */
@Component
public class UploadFileValidator {

	static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

	private final int maxFiles;
	// 설정(app.upload.text-ext, doc-ext)에 있는 확장자만 받는다. 아래 FileType에 없는 확장자는 설정에 있어도 받지 않는다.
	private final Set<String> allowedExtensions;

	public UploadFileValidator(
			@Value("${app.upload.max-files:10}") int maxFiles,
			@Value("${app.upload.text-ext:txt,md,csv,json,xml,yaml,yml,log}") List<String> textExtensions,
			@Value("${app.upload.doc-ext:pdf,docx}") List<String> docExtensions) {
		this.maxFiles = maxFiles;
		this.allowedExtensions = Stream.concat(textExtensions.stream(), docExtensions.stream())
				.map(ext -> ext.trim().toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * 지원 파일 형식 (API 설계서 2.6, DB 설계서 4.3). 저장할 MIME 타입은 클라이언트 값이 아니라 여기서 정한다.
	 * html·svg는 마크업으로 해석되면 저장형 XSS가 되므로 일부러 넣지 않는다.
	 */
	enum FileType {
		PDF("pdf", "application/pdf", FileKind.DOC, new byte[] {'%', 'P', 'D', 'F'}),
		DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileKind.DOC,
				new byte[] {'P', 'K', 0x03, 0x04}),
		TXT("txt", "text/plain", FileKind.TEXT, null),
		MD("md", "text/markdown", FileKind.TEXT, null),
		CSV("csv", "text/csv", FileKind.TEXT, null),
		JSON("json", "application/json", FileKind.TEXT, null),
		XML("xml", "application/xml", FileKind.TEXT, null),
		YAML("yaml", "application/yaml", FileKind.TEXT, null),
		YML("yml", "application/yaml", FileKind.TEXT, null),
		LOG("log", "text/plain", FileKind.TEXT, null);

		private final String extension;
		private final String mimeType;
		private final FileKind kind;
		private final byte[] magic; // 텍스트 계열은 매직 넘버가 없다

		FileType(String extension, String mimeType, FileKind kind, byte[] magic) {
			this.extension = extension;
			this.mimeType = mimeType;
			this.kind = kind;
			this.magic = magic;
		}

		static Optional<FileType> fromExtension(String extension) {
			return Arrays.stream(values())
					.filter(type -> type.extension.equals(extension))
					.findFirst();
		}
	}

	/**
	 * 검증을 통과한 파일 < API 18 >
	 *
	 * @param file             업로드된 원본 파일
	 * @param originalFilename 표시용 원본 파일명 (저장 경로에는 쓰지 않는다)
	 * @param extension        소문자 확장자 (pdf, docx, txt, md ...)
	 * @param mimeType         확장자로 정한 MIME 타입
	 * @param kind             계열. TEXT면 업로드 트랜잭션에서 본문을 채우고 READY, DOC면 PROCESSING
	 * @param size             파일 크기 (byte)
	 * @param contentHash      파일 내용의 SHA-256 (64자리 16진수)
	 */
	public record ValidatedFile(
			MultipartFile file,
			String originalFilename,
			String extension,
			String mimeType,
			FileKind kind,
			long size,
			String contentHash
	) {
	}

	/**
	 * 업로드 파일 전체 검증 < API 18 >
	 *
	 * @param files        요청의 files (배열 순서 = 버전 순서)
	 * @param previousHash 문서의 현재 마지막 버전 content_hash. 버전이 없으면 null
	 * @return 요청 순서를 유지한 검증 결과
	 * @throws UploadValidationException 검증 실패 시 (실패한 단계가 reason에 담긴다)
	 */
	public List<ValidatedFile> validate(List<MultipartFile> files, String previousHash) {
		// 2단계: 파일 개수 1~10개, 빈 파일 거부
		if (files == null || files.isEmpty() || files.size() > maxFiles) {
			throw new UploadValidationException(Reason.FILE_COUNT);
		}
		for (MultipartFile file : files) {
			if (file == null || file.isEmpty()) {
				throw new UploadValidationException(Reason.FILE_COUNT);
			}
		}

		// 3단계: 확장자로 계열 판정 후 계열별 내용 검증 (확장자만 바꾼 위장 파일 차단). 한 요청에 두 계열이 섞여도 파일마다 따로 판정
		List<FileType> types = new ArrayList<>();
		for (MultipartFile file : files) {
			String extension = extensionOf(file.getOriginalFilename());
			FileType type = FileType.fromExtension(extension)
					.filter(t -> allowedExtensions.contains(t.extension))
					.orElseThrow(() -> new UploadValidationException(Reason.INVALID_TYPE));
			boolean valid = type.kind == FileKind.DOC ? startsWith(file, type.magic) : isPlainUtf8Text(file);
			if (!valid) {
				throw new UploadValidationException(Reason.INVALID_TYPE);
			}
			types.add(type);
		}

		// 4단계: 파일당 20MB
		for (MultipartFile file : files) {
			if (file.getSize() > MAX_FILE_SIZE) {
				throw new UploadValidationException(Reason.FILE_TOO_LARGE);
			}
		}

		// 5단계: 파일명 검사 (저장에는 UUID 파일명을 쓰지만, 표시용 이름도 경로 형태는 거부)
		for (MultipartFile file : files) {
			if (!isSafeFilename(file.getOriginalFilename())) {
				throw new UploadValidationException(Reason.INVALID_FILENAME);
			}
		}

		// 6단계: 직전 버전과 같은 내용 거부 (배치 안에서도 바로 앞 파일과 비교)
		List<ValidatedFile> result = new ArrayList<>();
		String previous = previousHash;
		for (int i = 0; i < files.size(); i++) {
			MultipartFile file = files.get(i);
			String hash = sha256(file);
			if (hash.equals(previous)) {
				throw new UploadValidationException(Reason.DUPLICATE_CONTENT);
			}
			FileType type = types.get(i);
			result.add(new ValidatedFile(file, file.getOriginalFilename(), type.extension, type.mimeType,
					type.kind, file.getSize(), hash));
			previous = hash;
		}
		return result;
	}

	/** 확장자를 소문자로 반환한다. 확장자가 없으면 빈 문자열 */
	private static String extensionOf(String filename) {
		if (filename == null) {
			return "";
		}
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/** 문서 계열: 파일 앞부분이 매직 넘버와 같은지 확인한다. */
	private static boolean startsWith(MultipartFile file, byte[] magic) {
		try (InputStream in = file.getInputStream()) {
			byte[] head = in.readNBytes(magic.length);
			return Arrays.equals(head, magic);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * 텍스트 계열: 파일 전체가 올바른 UTF-8이고, 널 바이트와 \t·\r·\n 외 C0 제어문자(0x00~0x1F)가 없는지 확인한다.
	 * 텍스트 계열은 매직 넘버가 없으므로 내용 전체를 읽는다. 메모리에 다 올리지 않고 나눠서 읽는다.
	 */
	private static boolean isPlainUtf8Text(MultipartFile file) {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try (Reader reader = new InputStreamReader(file.getInputStream(), decoder)) {
			char[] buffer = new char[8192];
			int read;
			while ((read = reader.read(buffer)) > 0) {
				for (int i = 0; i < read; i++) {
					char c = buffer[i];
					if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
						return false; // 널 바이트(0x00) 포함
					}
				}
			}
			return true;
		} catch (CharacterCodingException e) {
			return false; // UTF-8이 아님
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * 경로 조작에 쓰일 수 있는 파일명을 거부한다.
	 * '/'와 '\'는 상위 경로(../)와 절대 경로를 모두 막고, 'C:' 형태의 드라이브 경로와 널 바이트도 막는다.
	 */
	private static boolean isSafeFilename(String filename) {
		return filename != null
				&& !filename.isBlank()
				&& filename.indexOf('/') < 0
				&& filename.indexOf('\\') < 0
				&& filename.indexOf('\0') < 0
				&& !filename.matches("^[A-Za-z]:.*");
	}

	/** 파일 전체를 메모리에 올리지 않고 SHA-256을 계산한다. */
	private static String sha256(MultipartFile file) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		try (InputStream in = file.getInputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
