package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.response.VersionContentResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 원문 보기 서비스 단위 테스트 < API 23 > — DB 대신 가짜 Repository를 쓴다. */
class VersionServiceTest {

	private static final long DOCUMENT_ID = 7L;

	private DocumentVersionRepository versions;
	private Document document;

	@BeforeEach
	void setUp() {
		versions = mock(DocumentVersionRepository.class);
		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		document = Document.builder().name("운영지침").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
	}

	private DocumentVersion textVersion(int versionNo, String text) {
		DocumentVersion version = DocumentVersion.builder()
				.document(document)
				.versionNo(versionNo)
				.originalFilename("운영지침_v" + versionNo + ".md")
				.storedFilename("uuid-" + versionNo + ".md")
				.mimeType("text/markdown")
				.fileSize(text.length())
				.contentHash("hash-" + versionNo)
				.build();
		version.completeExtraction(text);
		return version;
	}

	@Test
	@DisplayName("버전의 본문을 돌려준다")
	void returnsVersionContent() {
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 3)).thenReturn(Optional.of(textVersion(3, "1. 목적")));

		VersionContentResponse response = new VersionService(null, versions, null, null, null, null, null, 1_048_576).getContent(document, 3);

		assertThat(response.versionNo()).isEqualTo(3);
		assertThat(response.content()).isEqualTo("1. 목적");
		assertThat(response.truncated()).isFalse();
	}

	@Test
	@DisplayName("설정한 상한으로 자른다")
	void truncatesToConfiguredLimit() {
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 1)).thenReturn(Optional.of(textVersion(1, "가".repeat(10))));

		VersionContentResponse response = new VersionService(null, versions, null, null, null, null, null, 10).getContent(document, 1);

		assertThat(response.content()).isEqualTo("가가가");
		assertThat(response.truncated()).isTrue();
	}

	@Test
	@DisplayName("없는 버전이면 404 DOCUMENT_NOT_FOUND")
	void missingVersionIs404() {
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> new VersionService(null, versions, null, null, null, null, null, 1_048_576).getContent(document, 9))
				.isInstanceOf(VersionNotFoundException.class)
				.extracting(e -> ((VersionNotFoundException) e).getErrorCode())
				.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
	}
}
