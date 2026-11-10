package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rookies6.DoQFlowAI.repository.DocumentRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

/** 기동 시 끊긴 업로드 후처리 복구. 실제 상태 변경은 ProcessingRecoveryIntegrationTest가 DB로 확인한다 */
class ProcessingRecoveryTest {

	@Test
	@DisplayName("버전과 문서의 PROCESSING을 모두 FAILED로 바꾼다")
	void failsProcessingVersionsAndDocuments() {
		DocumentRepository documents = mock(DocumentRepository.class);
		DocumentVersionRepository versions = mock(DocumentVersionRepository.class);

		new ProcessingRecovery(documents, versions).recover();

		verify(versions).failAllProcessing();
		verify(documents).failAllProcessing(any());
	}

	@Test
	@DisplayName("앱이 요청을 받기 직전(ApplicationReadyEvent)에 한 트랜잭션으로 실행된다")
	void runsOnApplicationReadyInTransaction() throws NoSuchMethodException {
		Method recover = ProcessingRecovery.class.getMethod("recover");

		assertThat(recover.getAnnotation(EventListener.class).value()).containsExactly(ApplicationReadyEvent.class);
		assertThat(recover.isAnnotationPresent(Transactional.class)).isTrue();
	}
}
