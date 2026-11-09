package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.exception.UploadValidationException;
import com.rookies6.DoQFlowAI.exception.UploadValidationException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * 확장자 + 매직 넘버 (PDF {@code %PDF}, DOCX {@code PK\x03\x04}) → 400<
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

	public UploadFileValidator(@Value("${app.upload.max-files:10}") int maxFiles) {
		this.maxFiles = maxFiles;
	}

	// 허용 파일 형식. 저장할 MIME 타입은 클라이언트 값이 아니라 여기서 정한다.
	enum FileType {
		PDF("pdf", "application/pdf", new byte[] {'%', 'P', 'D', 'F'}),
		DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
				new byte[] {'P', 'K', 0x03, 0x04});

		private final String extension;
		private final String mimeType;
		private final byte[] magic;

		FileType(String extension, String mimeType, byte[] magic) {
			this.extension = extension;
			this.mimeType = mimeType;
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
	 * @param extension        소문자 확장자 (pdf, docx)
	 * @param mimeType         확장자로 정한 MIME 타입
	 * @param size             파일 크기 (byte)
	 * @param contentHash      파일 내용의 SHA-256 (64자리 16진수)
	 */
	public record ValidatedFile(
			MultipartFile file,
			String originalFilename,
			String extension,
			String mimeType,
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
				throw new UploadValidationException(Reason.EMPTY_FILE);
			}
		}

		// 3단계: 확장자 + 매직 넘버 (확장자만 바꾼 위장 파일 차단)
		List<FileType> types = new ArrayList<>();
		for (MultipartFile file : files) {
			FileType type = FileType.fromExtension(extensionOf(file.getOriginalFilename()))
					.orElseThrow(() -> new UploadValidationException(Reason.UNSUPPORTED_TYPE));
			if (!startsWith(file, type.magic)) {
				throw new UploadValidationException(Reason.UNSUPPORTED_TYPE);
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
				throw new UploadValidationException(Reason.SAME_AS_PREVIOUS);
			}
			FileType type = types.get(i);
			result.add(new ValidatedFile(file, file.getOriginalFilename(), type.extension, type.mimeType,
					file.getSize(), hash));
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

	/** 파일 앞부분이 매직 넘버와 같은지 확인한다. */
	private static boolean startsWith(MultipartFile file, byte[] magic) {
		try (InputStream in = file.getInputStream()) {
			byte[] head = in.readNBytes(magic.length);
			return Arrays.equals(head, magic);
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
