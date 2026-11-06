package com.rookies6.DoQFlowAI.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 답변 근거. 문서·버전이 삭제되면 해당 참조는 DB에서 NULL이 되고, 이름과 번호는 답변 당시 값으로 남는다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "message_source")
public class MessageSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "message_id", nullable = false)
	private ChatMessage message;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "document_id")
	private Document document;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "version_id")
	private DocumentVersion version;

	@Column(nullable = false, length = 200)
	private String documentName;

	@Column(nullable = false)
	private int versionNo;

	private Integer pageNumber;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String chunkText;

	@Builder
	private MessageSource(ChatMessage message, Document document, DocumentVersion version, String documentName,
			int versionNo, Integer pageNumber, String chunkText) {
		this.message = message;
		this.document = document;
		this.version = version;
		this.documentName = documentName;
		this.versionNo = versionNo;
		this.pageNumber = pageNumber;
		this.chunkText = chunkText;
	}
}
