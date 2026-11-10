package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.response.VersionDetailResponse;
import com.rookies6.DoQFlowAI.dto.response.VersionResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.SummaryStatus;
import com.rookies6.DoQFlowAI.entity.VersionStatus;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.exception.VersionNotFoundException;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** 버전 목록·단건 단위 테스트 < API 19, API 20 >  */
class VersionReadTest {

	private static final long DOCUMENT_ID = 7L;
	private static final Instant UPLOADED = Instant.parse("2026-11-06T09:30:00Z");

	private DocumentVersionRepository versions;
	private VersionService service;
	private Document document;

	@BeforeEach
	void setUp() {
		versions = mock(DocumentVersionRepository.class);
		service = new VersionService(null, versions, null, null, null, null, null, 1_048_576);
		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		document = Document.builder().name("운영지침").uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
	}

	private DocumentVersion version(int versionNo) {
		DocumentVersion version = DocumentVersion.builder()
				.document(document)
				.versionNo(versionNo)
				.originalFilename("v" + versionNo + ".pdf")
				.storedFilename("5f1c-uuid-" + versionNo + ".pdf")
				.mimeType("application/pdf")
				.fileSize(184320)
				.contentHash("hash-" + versionNo)
				.build();
		version.completeExtraction("본문");
		// DB에는 서버 시간대의 시각으로 들어 있다고 보고, 같은 순간을 서버 시간대로 넣는다
		ReflectionTestUtils.setField(version, "createdAt", LocalDateTime.ofInstant(UPLOADED, ZoneId.systemDefault()));
		return version;
	}

	@Test
	@DisplayName("목록은 버전 순서대로 내리고 v1의 변경점은 null")
	void listsInOrderAndV1HasNoChange() {
		DocumentVersion v1 = version(1);
		DocumentVersion v2 = version(2);
		v2.updateSummary(SummaryStatus.OK, "PROCEDURE", "승인자 변경");
		when(versions.findByDocumentIdOrderByVersionNo(DOCUMENT_ID)).thenReturn(List.of(v1, v2));

		List<VersionResponse> response = service.list(document);

		assertThat(response).extracting(VersionResponse::versionNo).containsExactly(1, 2);
		assertThat(response.get(0).changeType()).isNull();
		assertThat(response.get(1).changeType()).isEqualTo("PROCEDURE");
		assertThat(response.get(1).changeSummary()).isEqualTo("승인자 변경");
	}

	@Test
	@DisplayName("버전이 없으면 빈 목록")
	void emptyWhenNoVersions() {
		when(versions.findByDocumentIdOrderByVersionNo(DOCUMENT_ID)).thenReturn(List.of());

		assertThat(service.list(document)).isEmpty();
	}

	@Test
	@DisplayName("단건은 해당 버전을 본문과 함께 내린다")
	void getsSingleVersionWithContent() {
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 2)).thenReturn(Optional.of(version(2)));

		VersionDetailResponse response = service.get(document, 2);

		assertThat(response.versionNo()).isEqualTo(2);
		assertThat(response.status()).isEqualTo(VersionStatus.READY);
		assertThat(response.fileSize()).isEqualTo(184320);
		assertThat(response.contentText()).isEqualTo("본문");
		assertThat(response.contentTruncated()).isFalse();
	}

	@Test
	@DisplayName("단건 본문은 READY가 아니면 null이다")
	void detailContentIsNullUnlessReady() {
		DocumentVersion processing = DocumentVersion.builder().document(document).versionNo(3)
				.originalFilename("v3.pdf").storedFilename("s3.pdf").mimeType("application/pdf")
				.fileSize(1).contentHash("h3").build();
		DocumentVersion failed = version(4);
		failed.failExtraction();
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 3)).thenReturn(Optional.of(processing));
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 4)).thenReturn(Optional.of(failed));

		assertThat(service.get(document, 3).contentText()).isNull();
		assertThat(service.get(document, 4).contentText()).isNull();
	}

	@Test
	@DisplayName("단건 본문은 상한을 넘으면 잘리고 잘렸다고 표시한다")
	void detailContentIsTruncatedOverLimit() {
		VersionService small = new VersionService(null, versions, null, null, null, null, null, 6);
		DocumentVersion longText = version(5);
		longText.completeExtraction("가나다라");
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 5)).thenReturn(Optional.of(longText));

		VersionDetailResponse response = small.get(document, 5);

		assertThat(response.contentText()).isEqualTo("가나");
		assertThat(response.contentTruncated()).isTrue();
	}

	@Test
	@DisplayName("단건 JSON은 목록 필드에 contentText와 contentTruncated를 더하고 저장 파일명이 없다")
	void detailJsonAddsContentWithoutStoredFilename() {
		String json = JsonMapper.builder().build().writeValueAsString(VersionDetailResponse.of(version(1), 1_048_576));

		assertThat(json).isEqualTo("{\"versionNo\":1,\"originalFilename\":\"v1.pdf\",\"fileSize\":184320,"
				+ "\"status\":\"READY\",\"changeType\":null,\"changeSummary\":null,"
				+ "\"createdAt\":\"2026-11-06T09:30:00Z\",\"contentText\":\"본문\",\"contentTruncated\":false}");
		assertThat(json).doesNotContain("storedFilename", "5f1c-uuid", "contentHash");
	}

	@Test
	@DisplayName("없는 버전이면 404 DOCUMENT_NOT_FOUND")
	void missingVersionIs404() {
		when(versions.findByDocumentIdAndVersionNo(DOCUMENT_ID, 9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get(document, 9))
				.isInstanceOf(VersionNotFoundException.class)
				.extracting(e -> ((VersionNotFoundException) e).getErrorCode())
				.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
	}

	@Test
	@DisplayName("JSON은 API 설계서 필드와 같고 저장 파일명이 없다")
	void jsonMatchesApiFieldsWithoutStoredFilename() {
		String json = JsonMapper.builder().build().writeValueAsString(VersionResponse.from(version(1)));

		assertThat(json).isEqualTo("{\"versionNo\":1,\"originalFilename\":\"v1.pdf\",\"fileSize\":184320,"
				+ "\"status\":\"READY\",\"changeType\":null,\"changeSummary\":null,"
				+ "\"createdAt\":\"2026-11-06T09:30:00Z\"}");
		assertThat(json).doesNotContain("storedFilename", "5f1c-uuid", "contentHash", "본문");
	}
}
