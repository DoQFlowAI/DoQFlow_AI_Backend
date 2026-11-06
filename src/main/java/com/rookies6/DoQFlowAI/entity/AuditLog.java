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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "audit_log")
public class AuditLog extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "actor_user_id")
	private AppUser actorUser;

	@Column(nullable = false, length = 40)
	private String eventType;

	@Column(length = 20)
	private String targetType;

	private Long targetId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private AuditResult result;

	@Column(length = 45)
	private String ipAddress;

	@Column(length = 36)
	private String traceId;

	@Column(columnDefinition = "JSON")
	private String detailJson;

	@Builder
	private AuditLog(AppUser actorUser, String eventType, String targetType, Long targetId, AuditResult result,
			String ipAddress, String traceId, String detailJson) {
		this.actorUser = actorUser;
		this.eventType = eventType;
		this.targetType = targetType;
		this.targetId = targetId;
		this.result = result;
		this.ipAddress = ipAddress;
		this.traceId = traceId;
		this.detailJson = detailJson;
	}
}
