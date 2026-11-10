package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.ai.AiChat;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.ChatMessage;
import com.rookies6.DoQFlowAI.entity.ChatSession;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.MessageRole;
import com.rookies6.DoQFlowAI.entity.MessageSource;
import com.rookies6.DoQFlowAI.exception.ChatSessionNotFoundException;
import com.rookies6.DoQFlowAI.repository.ChatMessageRepository;
import com.rookies6.DoQFlowAI.repository.ChatSessionRepository;
import com.rookies6.DoQFlowAI.repository.DocumentVersionRepository;
import com.rookies6.DoQFlowAI.repository.MessageSourceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 질문·답변·근거 저장 단위 테스트 < API 27 > — DB 대신 가짜 Repository를 쓴다. */
class ChatHistoryWriterTest {

	private static final long SESSION_ID = 12L;

	private ChatSessionRepository sessions;
	private ChatMessageRepository messages;
	private MessageSourceRepository sources;
	private DocumentVersionRepository versions;
	private ChatHistoryWriter writer;
	private ChatSession session;
	private final List<ChatMessage> savedMessages = new ArrayList<>();
	private final List<MessageSource> savedSources = new ArrayList<>();

	@BeforeEach
	void setUp() {
		sessions = mock(ChatSessionRepository.class);
		messages = mock(ChatMessageRepository.class);
		sources = mock(MessageSourceRepository.class);
		versions = mock(DocumentVersionRepository.class);
		writer = new ChatHistoryWriter(sessions, messages, sources, versions);

		AppUser me = AppUser.builder().email("staff@example.com").passwordHash("x").name("사원").rankLevel(1).build();
		session = ChatSession.builder().user(me).build();
		when(sessions.findById(SESSION_ID)).thenReturn(Optional.of(session));

		AtomicLong ids = new AtomicLong(100);
		when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
			ChatMessage message = inv.getArgument(0);
			ReflectionTestUtils.setField(message, "id", ids.incrementAndGet());
			savedMessages.add(message);
			return message;
		});
		when(sources.save(any(MessageSource.class))).thenAnswer(inv -> {
			savedSources.add(inv.getArgument(0));
			return inv.getArgument(0);
		});
	}

	private DocumentVersion version(long versionId, long documentId, String documentName, int versionNo) {
		AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
		Document document = Document.builder().name(documentName).uploadedBy(lead).build();
		ReflectionTestUtils.setField(document, "id", documentId);
		DocumentVersion version = DocumentVersion.builder().document(document).versionNo(versionNo)
				.originalFilename("v.pdf").storedFilename("s.pdf").mimeType("application/pdf")
				.fileSize(1).contentHash("h").build();
		ReflectionTestUtils.setField(version, "id", versionId);
		return version;
	}

	@Test
	@DisplayName("답변과 근거를 문서 이름과 버전 번호 스냅샷으로 저장한다")
	void savesAnswerAndSourcesWithSnapshot() {
		when(versions.findAllById(List.of(33L))).thenReturn(List.of(version(33L, 7L, "출장 규정", 3)));
		AiChat.Result result = new AiChat.Result(List.of("출장비는", " 14일 이내"), List.of(33L),
				List.of(new AiChat.Chunk(33L, 7L, 3, 12, 5, "정산은 14일 이내"),
						new AiChat.Chunk(33L, 7L, 3, null, 6, null)), false);

		ChatAnswer answer = writer.saveAnswer(SESSION_ID, "출장비 정산 기한이 언제야?", result).orElseThrow();

		assertThat(savedMessages).extracting(ChatMessage::getRole)
				.containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
		assertThat(savedMessages.get(1).getContent()).isEqualTo("출장비는 14일 이내");
		assertThat(answer.messageId()).isEqualTo(savedMessages.get(1).getId());
		assertThat(answer.noAnswer()).isFalse();
		assertThat(answer.tokens()).containsExactly("출장비는", " 14일 이내");

		assertThat(savedSources).hasSize(2);
		MessageSource first = savedSources.get(0);
		assertThat(first.getDocumentName()).isEqualTo("출장 규정");
		assertThat(first.getVersionNo()).isEqualTo(3);
		assertThat(first.getPageNumber()).isEqualTo(12);
		assertThat(first.getVersion().getId()).isEqualTo(33L);
		assertThat(savedSources.get(1).getChunkText()).isEmpty();
		assertThat(answer.sources()).extracting(s -> s.documentId()).containsExactly(7L, 7L);
		assertThat(session.getTitle()).isEqualTo("출장비 정산 기한이 언제야?");
	}

	@Test
	@DisplayName("근거 버전이 그사이 삭제됐으면 아무것도 저장하지 않는다")
	void savesNothingWhenSourceVersionDeleted() {
		when(versions.findAllById(List.of(33L))).thenReturn(List.of());
		AiChat.Result result = new AiChat.Result(List.of("답"), List.of(33L),
				List.of(new AiChat.Chunk(33L, 7L, 3, 1, 0, "조각")), false);

		assertThat(writer.saveAnswer(SESSION_ID, "질문", result)).isEmpty();
		verify(messages, never()).save(any());
		verify(sources, never()).save(any());
	}

	@Test
	@DisplayName("근거 없음은 질문과 고정 문구만 저장한다")
	void noAnswerSavesQuestionAndFixedMessageOnly() {
		ChatAnswer answer = writer.saveNoAnswer(SESSION_ID, "질문", ChatService.NO_ANSWER_MESSAGE);

		assertThat(answer.noAnswer()).isTrue();
		assertThat(savedMessages).extracting(ChatMessage::getContent)
				.containsExactly("질문", ChatService.NO_ANSWER_MESSAGE);
		verify(sources, never()).save(any());
	}

	@Test
	@DisplayName("제목은 첫 질문으로만 채우고 50자에서 자른다")
	void titleComesFromFirstQuestionCutAt50() {
		writer.saveNoAnswer(SESSION_ID, "  첫   질문\n입니다  ", ChatService.NO_ANSWER_MESSAGE);
		writer.saveNoAnswer(SESSION_ID, "두 번째 질문", ChatService.NO_ANSWER_MESSAGE);
		assertThat(session.getTitle()).isEqualTo("첫 질문 입니다");

		assertThat(ChatHistoryWriter.title("가".repeat(60))).isEqualTo("가".repeat(50));
	}

	@Test
	@DisplayName("질문 처리 중 대화가 지워졌으면 404다")
	void sessionDeletedDuringQuestionIs404() {
		when(sessions.findById(SESSION_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> writer.saveNoAnswer(SESSION_ID, "질문", ChatService.NO_ANSWER_MESSAGE))
				.isInstanceOf(ChatSessionNotFoundException.class);
		verify(messages, never()).save(any());
	}
}
