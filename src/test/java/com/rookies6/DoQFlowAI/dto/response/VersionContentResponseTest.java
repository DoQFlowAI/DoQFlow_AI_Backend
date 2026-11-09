package com.rookies6.DoQFlowAI.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.service.FileKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/// 원문 보기 응답 단위 테스트 < API 23 >
class VersionContentResponseTest {

	private static final int MAX_BYTES = 1_048_576;
	private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

	private final Document document = Document.builder()
			.name("운영지침")
			.uploadedBy(AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build())
			.build();

	private DocumentVersion version(String filename, String mimeType) {
		return DocumentVersion.builder()
				.document(document)
				.versionNo(3)
				.originalFilename(filename)
				.storedFilename("5f1c-uuid." + filename.substring(filename.lastIndexOf('.') + 1))
				.mimeType(mimeType)
				.fileSize(100)
				.contentHash("hash")
				.build(); // 생성 직후 상태는 PROCESSING
	}

	@Test
	void 텍스트_계열은_kind_TEXT로_원문을_그대로_내린다() {
		DocumentVersion v = version("운영지침_v3.md", "text/markdown");
		v.completeExtraction("1. 목적\n이 지침은 ...");

		VersionContentResponse response = VersionContentResponse.of(v, MAX_BYTES);

		assertThat(response.versionNo()).isEqualTo(3);
		assertThat(response.originalFilename()).isEqualTo("운영지침_v3.md");
		assertThat(response.kind()).isEqualTo(FileKind.TEXT);
		assertThat(response.content()).isEqualTo("1. 목적\n이 지침은 ...");
		assertThat(response.truncated()).isFalse();
	}

	@Test
	void 문서_계열은_kind_DOC() {
		DocumentVersion pdf = version("v3.pdf", "application/pdf");
		pdf.completeExtraction("첫 문장입니다.");
		DocumentVersion docx = version("v3.docx", DOCX_MIME);
		docx.completeExtraction("첫 문장입니다.");

		assertThat(VersionContentResponse.of(pdf, MAX_BYTES).kind()).isEqualTo(FileKind.DOC);
		assertThat(VersionContentResponse.of(docx, MAX_BYTES).kind()).isEqualTo(FileKind.DOC);
	}

	@Test
	void 추출_전이나_추출_실패면_content는_null() {
		DocumentVersion processing = version("v3.pdf", "application/pdf");
		DocumentVersion failed = version("v4.pdf", "application/pdf");
		failed.failExtraction();

		assertThat(VersionContentResponse.of(processing, MAX_BYTES).content()).isNull();
		assertThat(VersionContentResponse.of(failed, MAX_BYTES).content()).isNull();
		assertThat(VersionContentResponse.of(failed, MAX_BYTES).truncated()).isFalse();
	}

	@Test
	void 상한을_넘으면_잘라서_truncated_true() {
		DocumentVersion v = version("big.log", "text/plain");
		v.completeExtraction("가".repeat(10));

		VersionContentResponse response = VersionContentResponse.of(v, 10);

		assertThat(response.content()).isEqualTo("가가가");
		assertThat(response.truncated()).isTrue();
	}

	@Test
	void JSON에_저장_파일명과_경로가_없다() {
		DocumentVersion v = version("운영지침_v3.md", "text/markdown");
		v.completeExtraction("본문");

		String json = JsonMapper.builder().build().writeValueAsString(VersionContentResponse.of(v, MAX_BYTES));

		assertThat(json).isEqualTo(
				"{\"versionNo\":3,\"originalFilename\":\"운영지침_v3.md\",\"kind\":\"TEXT\",\"content\":\"본문\",\"truncated\":false}");
		assertThat(json).doesNotContain("storedFilename", "5f1c-uuid", "mimeType");
	}
}
