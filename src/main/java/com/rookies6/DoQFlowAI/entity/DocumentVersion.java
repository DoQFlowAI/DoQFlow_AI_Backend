package com.rookies6.DoQFlowAI.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 버전 n의 행에 직전 버전(n-1)과의 구간 변경점을 함께 저장한다. v1의 변경점 컬럼은 null이다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "document_version",
		uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "version_no"}))
public class DocumentVersion extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "document_id", nullable = false)
	private Document document;

	@Column(nullable = false)
	private int versionNo;

	@Column(nullable = false)
	private String originalFilename;

	@Column(nullable = false, unique = true, length = 100)
	private String storedFilename;

	@Column(nullable = false, length = 100)
	private String mimeType;

	@Column(nullable = false)
	private long fileSize;

	@Column(nullable = false, length = 64)
	private String contentHash;

	@Column(columnDefinition = "LONGTEXT")
	private String contentText;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private VersionStatus status;

	/** AI Module이 판정한 값을 그대로 저장한다. (예: PROCEDURE) */
	@Column(length = 12)
	private String changeType;

	@Column(columnDefinition = "TEXT")
	private String changeSummary;

	private Integer changeCount;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private SummaryStatus summaryStatus;

	/** DiffBlock 배열의 JSON 문자열 */
	@Column(columnDefinition = "JSON")
	private String diffJson;

	@Builder
	private DocumentVersion(Document document, int versionNo, String originalFilename, String storedFilename,
			String mimeType, long fileSize, String contentHash) {
		this.document = document;
		this.versionNo = versionNo;
		this.originalFilename = originalFilename;
		this.storedFilename = storedFilename;
		this.mimeType = mimeType;
		this.fileSize = fileSize;
		this.contentHash = contentHash;
		this.status = VersionStatus.PROCESSING;
	}

	public void completeExtraction(String contentText) {
		this.contentText = contentText;
		this.status = VersionStatus.READY;
	}

	public void failExtraction() {
		this.status = VersionStatus.FAILED;
	}

	public void updateDiff(String diffJson, int changeCount) {
		this.diffJson = diffJson;
		this.changeCount = changeCount;
	}

	public void updateSummary(SummaryStatus summaryStatus, String changeType, String changeSummary) {
		this.summaryStatus = summaryStatus;
		this.changeType = changeType;
		this.changeSummary = changeSummary;
	}
}
