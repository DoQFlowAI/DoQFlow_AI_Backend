package com.rookies6.DoQFlowAI.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 생성 시각만 공통으로 둔다. updatedAt은 값이 바뀌는 Document에만 선언한다.
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity {

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}
}
