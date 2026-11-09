package com.rookies6.DoQFlowAI.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rookies6.DoQFlowAI.dto.ai.AiChat;
import com.rookies6.DoQFlowAI.dto.response.ChatMessageResponse;
import com.rookies6.DoQFlowAI.dto.response.ChatSourceResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.ChatMessage;
import com.rookies6.DoQFlowAI.entity.ChatSession;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.DocumentVersion;
import com.rookies6.DoQFlowAI.entity.MessageRole;
import com.rookies6.DoQFlowAI.entity.MessageSource;
import com.rookies6.DoQFlowAI.exception.AiModuleException;
import com.rookies6.DoQFlowAI.exception.BusinessException;
import com.rookies6.DoQFlowAI.exception.ChatSessionNotFoundException;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.repository.ChatMessageRepository;
import com.rookies6.DoQFlowAI.repository.ChatSessionRepository;
import com.rookies6.DoQFlowAI.repository.MessageSourceRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ChatService 단위 테스트 < API 25, 26, 27 >
 * DB와 AI Module 대신 가짜 Repository·AiClientService·ChatHistoryWriter를 쓴다.
 * 핵심은 "LLM에 가기 전에 막는가"와 "scope 밖 근거가 섞인 답변을 내보내지 않는가"다.
 */
class ChatServiceTest {

	private static final long SESSION_ID = 12L;
	private static final String QUESTION = "출장비 정산 기한이 언제야?";

	private ChatSessionRepository sessions;
	private ChatMessageRepository messages;
	private MessageSourceRepository sources;
	private ChatHistoryWriter writer;
	private AiClientService aiClient;
	private ChatService service;
	private AppUser me;
	private ChatSession session;

	@BeforeEach
	void setUp() {
		sessions = mock(ChatSessionRepository.class);
		messages = mock(ChatMessageRepository.class);
		sources = mock(MessageSourceRepository.class);
		writer = mock(ChatHistoryWriter.class);
		aiClient = mock(AiClientService.class);
		service = new ChatService(sessions, messages, sources, writer, aiClient, 1000, 5);

		me = AppUser.builder().email("staff@example.com").passwordHash("x").name("사원").rankLevel(1).build();
		ReflectionTestUtils.setField(me, "id", 3L);
		session = ChatSession.builder().user(me).build();
		ReflectionTestUtils.setField(session, "id", SESSION_ID);
		when(sessions.findByIdAndUserId(SESSION_ID, 3L)).thenReturn(Optional.of(session));
		when(writer.saveNoAnswer(anyLong(), anyString(), anyString())).thenReturn(ChatAnswer.noAnswer(99L));
	}

	private static AiChat.Chunk chunk(long versionId) {
		return new AiChat.Chunk(versionId, 7L, 3, 12, 0, "조각");
	}

	private static AiChat.Result answer(List<Long> sourceVersionIds, List<AiChat.Chunk> chunks) {
		return new AiChat.Result(List.of("출장비는", " 14일 이내"), sourceVersionIds, chunks, false);
	}

	@Test
	void 대화를_만들면_제목_없이_내_대화로_저장된다() {
		when(sessions.save(any(ChatSession.class))).thenAnswer(inv -> {
			ChatSession saved = inv.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 20L);
			return saved;
		});

		var response = service.createSession(me);

		assertThat(response.sessionId()).isEqualTo(20L);
		assertThat(response.title()).isNull();
	}

	@Nested
	class LLM_호출_전에_막는다 {

		@Test
		void scope가_비면_AI_Module을_호출하지_않고_근거_없음으로_끝낸다() {
			ChatAnswer result = service.ask(me, SESSION_ID, QUESTION, List.of());

			assertThat(result.noAnswer()).isTrue();
			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		void scope가_null이어도_호출하지_않는다() {
			service.ask(me, SESSION_ID, QUESTION, null);

			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
		}

		@Test
		void 남의_대화거나_없는_대화면_404이고_호출하지_않는다() {
			assertThatThrownBy(() -> service.ask(me, 999L, QUESTION, List.of(33L)))
					.isInstanceOf(ChatSessionNotFoundException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
			verify(writer, never()).saveNoAnswer(anyLong(), anyString(), anyString());
		}

		@Test
		void 질문이_비었거나_1000자를_넘으면_400이고_호출하지_않는다() {
			for (String bad : new String[] {null, "", "   ", "가".repeat(1001)}) {
				assertThatThrownBy(() -> service.ask(me, SESSION_ID, bad, List.of(33L)))
						.isInstanceOf(BusinessException.class)
						.extracting(e -> ((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.VALIDATION_ERROR);
			}
			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
		}

		@Test
		void 질문은_1000자까지_받는다() {
			service.ask(me, SESSION_ID, "가".repeat(1000), List.of());

			verify(writer).saveNoAnswer(eq(SESSION_ID), anyString(), anyString());
		}
	}

	@Nested
	class 근거_재확인 {

		@Test
		void 통과한_답변만_저장하고_돌려준다() {
			AiChat.Result result = answer(List.of(33L), List.of(chunk(33L)));
			when(aiClient.chat(QUESTION, List.of(33L, 41L), 5)).thenReturn(result);
			ChatAnswer saved = new ChatAnswer(100L, false, result.tokens(),
					List.of(new ChatSourceResponse(7L, "출장 규정", 3, 12, "조각")));
			when(writer.saveAnswer(SESSION_ID, QUESTION, result)).thenReturn(Optional.of(saved));

			ChatAnswer answer = service.ask(me, SESSION_ID, QUESTION, List.of(41L, 33L, 41L));

			assertThat(answer).isEqualTo(saved);
			verify(writer, never()).saveNoAnswer(anyLong(), anyString(), anyString());
		}

		@Test
		void sourceVersionIds가_하나라도_scope_밖이면_답변을_버린다() {
			when(aiClient.chat(anyString(), anyList(), anyInt()))
					.thenReturn(answer(List.of(33L, 52L), List.of(chunk(33L))));

			ChatAnswer answer = service.ask(me, SESSION_ID, QUESTION, List.of(33L));

			assertThat(answer.noAnswer()).isTrue();
			assertThat(answer.tokens()).isEmpty();
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		void 근거_조각의_버전이_scope_밖이면_답변을_버린다() {
			when(aiClient.chat(anyString(), anyList(), anyInt()))
					.thenReturn(answer(List.of(33L), List.of(chunk(33L), chunk(52L))));

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
		}

		@Test
		void 근거가_없는_답변은_버린다() {
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenReturn(answer(List.of(), List.of()));

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
		}

		@Test
		void AI_Module이_근거_없음이면_근거_없음으로_저장한다() {
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenReturn(AiChat.Result.noAnswerResult());

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		void 근거_버전이_그사이_삭제되면_근거_없음이다() {
			AiChat.Result result = answer(List.of(33L), List.of(chunk(33L)));
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenReturn(result);
			when(writer.saveAnswer(SESSION_ID, QUESTION, result)).thenReturn(Optional.empty());

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		void AI_Module_실패는_502로_올리고_아무것도_저장하지_않는다() {
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenThrow(new AiModuleException("down"));

			assertThatThrownBy(() -> service.ask(me, SESSION_ID, QUESTION, List.of(33L)))
					.isInstanceOf(AiModuleException.class);
			verify(writer, never()).saveNoAnswer(anyLong(), anyString(), anyString());
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
		}
	}

	@Nested
	class 대화_기록_가림 {

		private Document document(long id) {
			Document document = Document.builder().name("문서" + id).uploadedBy(me).build();
			ReflectionTestUtils.setField(document, "id", id);
			return document;
		}

		private DocumentVersion version(Document document) {
			return DocumentVersion.builder().document(document).versionNo(3).originalFilename("v.pdf")
					.storedFilename("s.pdf").mimeType("application/pdf").fileSize(1).contentHash("h").build();
		}

		private ChatMessage message(long id, MessageRole role, String content) {
			ChatMessage message = ChatMessage.builder().session(session).role(role).content(content).build();
			ReflectionTestUtils.setField(message, "id", id);
			return message;
		}

		private MessageSource source(ChatMessage answer, DocumentVersion version) {
			return MessageSource.builder().message(answer).version(version).documentName("당시 이름")
					.versionNo(3).pageNumber(12).chunkText("조각").build();
		}

		@Test
		void 읽을_수_있는_근거면_그대로_보이고_권한을_잃은_근거나_삭제된_근거가_있으면_가린다() {
			Document readable = document(7L);
			Document revoked = document(8L);
			ChatMessage q1 = message(1L, MessageRole.USER, "질문1");
			ChatMessage a1 = message(2L, MessageRole.ASSISTANT, "답변1");
			ChatMessage q2 = message(3L, MessageRole.USER, "질문2");
			ChatMessage a2 = message(4L, MessageRole.ASSISTANT, "답변2");
			ChatMessage q3 = message(5L, MessageRole.USER, "질문3");
			ChatMessage a3 = message(6L, MessageRole.ASSISTANT, "답변3");
			when(messages.findBySessionIdOrderByCreatedAtAscIdAsc(SESSION_ID))
					.thenReturn(List.of(q1, a1, q2, a2, q3, a3));
			when(sources.findByMessageIdIn(List.of(2L, 4L, 6L))).thenReturn(List.of(
					source(a1, version(readable)),
					source(a2, version(readable)), source(a2, version(revoked)),
					source(a3, null)));
			Predicate<Document> canRead = doc -> doc.getId().equals(7L);

			List<ChatMessageResponse> history = service.listMessages(me, SESSION_ID, canRead);

			assertThat(history).extracting(ChatMessageResponse::messageId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
			assertThat(history.get(0).masked()).isNull();
			assertThat(history.get(0).sources()).isNull();

			ChatMessageResponse shown = history.get(1);
			assertThat(shown.masked()).isFalse();
			assertThat(shown.content()).isEqualTo("답변1");
			assertThat(shown.sources()).singleElement().satisfies(s -> {
				assertThat(s.documentId()).isEqualTo(7L);
				assertThat(s.documentName()).isEqualTo("당시 이름");
			});

			for (ChatMessageResponse hidden : List.of(history.get(3), history.get(5))) {
				assertThat(hidden.masked()).isTrue();
				assertThat(hidden.content()).isEmpty();
				assertThat(hidden.sources()).isEmpty();
			}
		}

		@Test
		void 근거_없음_답변은_가리지_않는다() {
			ChatMessage q = message(1L, MessageRole.USER, "질문");
			ChatMessage a = message(2L, MessageRole.ASSISTANT, ChatService.NO_ANSWER_MESSAGE);
			when(messages.findBySessionIdOrderByCreatedAtAscIdAsc(SESSION_ID)).thenReturn(List.of(q, a));
			when(sources.findByMessageIdIn(List.of(2L))).thenReturn(List.of());

			List<ChatMessageResponse> history = service.listMessages(me, SESSION_ID, doc -> false);

			assertThat(history.get(1).masked()).isFalse();
			assertThat(history.get(1).content()).isEqualTo(ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		void 같은_문서의_권한은_한_번만_판정한다() {
			Document readable = document(7L);
			ChatMessage a1 = message(2L, MessageRole.ASSISTANT, "답변1");
			ChatMessage a2 = message(4L, MessageRole.ASSISTANT, "답변2");
			when(messages.findBySessionIdOrderByCreatedAtAscIdAsc(SESSION_ID)).thenReturn(List.of(a1, a2));
			when(sources.findByMessageIdIn(List.of(2L, 4L))).thenReturn(List.of(
					source(a1, version(readable)), source(a2, version(readable)), source(a2, version(readable))));
			AtomicInteger calls = new AtomicInteger();

			service.listMessages(me, SESSION_ID, doc -> {
				calls.incrementAndGet();
				return true;
			});

			assertThat(calls).hasValue(1);
		}

		@Test
		void 남의_대화는_404다() {
			assertThatThrownBy(() -> service.listMessages(me, 999L, doc -> true))
					.isInstanceOf(ChatSessionNotFoundException.class);
			verify(messages, times(0)).findBySessionIdOrderByCreatedAtAscIdAsc(anyLong());
		}
	}
}
