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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "document")
public class Document extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 500)
	private String description;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "uploaded_by", nullable = false, updatable = false)
	private AppUser uploadedBy;

	@Column(nullable = false)
	private int currentVersionNo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private DocumentStatus status;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	private Document(String name, String description, AppUser uploadedBy) {
		this.name = name;
		this.description = description;
		this.uploadedBy = uploadedBy;
		this.currentVersionNo = 0;
		this.status = DocumentStatus.EMPTY;
	}

	/** null인 값은 변경하지 않는다. */
	public void update(String name, String description) {
		if (name != null) {
			this.name = name;
		}
		if (description != null) {
			this.description = description;
		}
	}

	public void changeCurrentVersion(int versionNo) {
		currentVersionNo = versionNo;
	}

	public void changeStatus(DocumentStatus status) {
		this.status = status;
	}

	@Override
	@PrePersist
	protected void onCreate() {
		super.onCreate();
		updatedAt = getCreatedAt();
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = LocalDateTime.now();
	}
}
