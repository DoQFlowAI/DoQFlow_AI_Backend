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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 초대 시 PENDING으로 만든다. responded_at이 변경 시각 역할을 한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "document_permission",
		uniqueConstraints = @UniqueConstraint(columnNames = {"document_id", "user_id"}))
public class DocumentPermission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "document_id", nullable = false)
	private Document document;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "granted_by", nullable = false)
	private AppUser grantedBy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PermissionStatus status;

	@Column(nullable = false, updatable = false)
	private LocalDateTime grantedAt;

	private LocalDateTime respondedAt;

	@Builder
	private DocumentPermission(Document document, AppUser user, AppUser grantedBy) {
		this.document = document;
		this.user = user;
		this.grantedBy = grantedBy;
		this.status = PermissionStatus.PENDING;
	}

	@PrePersist
	protected void onCreate() {
		grantedAt = LocalDateTime.now();
	}
}
