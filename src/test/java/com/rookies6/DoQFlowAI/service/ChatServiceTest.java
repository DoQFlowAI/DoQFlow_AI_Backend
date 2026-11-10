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
import com.rookies6.DoQFlowAI.repository.DocumentPermissionRepository;
import com.rookies6.DoQFlowAI.repository.MessageSourceRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
	private DocumentPermissionRepository permissions;
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
		permissions = mock(DocumentPermissionRepository.class);
		// isOwner는 문서의 등록자 ID만 본다. Repository는 쓰지 않으므로 null
		AccessPolicy accessPolicy = new AccessPolicy(null, null, null, null);
		service = new ChatService(sessions, messages, sources, writer, aiClient, accessPolicy, permissions, 1000, 5);

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
	@DisplayName("대화를 만들면 제목 없이 내 대화로 저장된다")
	void createsSessionWithoutTitle() {
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
	@DisplayName("LLM 호출 전에 막는다")
	class BlocksBeforeLlm {

		@Test
		@DisplayName("scope가 비면 AI Module을 호출하지 않고 근거 없음으로 끝낸다")
		void emptyScopeEndsWithNoAnswerWithoutCallingAi() {
			ChatAnswer result = service.ask(me, SESSION_ID, QUESTION, List.of());

			assertThat(result.noAnswer()).isTrue();
			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		@DisplayName("scope가 null이어도 호출하지 않는다")
		void nullScopeDoesNotCallAi() {
			service.ask(me, SESSION_ID, QUESTION, null);

			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
		}

		@Test
		@DisplayName("남의 대화거나 없는 대화면 404이고 호출하지 않는다")
		void othersOrMissingSessionIs404WithoutCallingAi() {
			assertThatThrownBy(() -> service.ask(me, 999L, QUESTION, List.of(33L)))
					.isInstanceOf(ChatSessionNotFoundException.class)
					.extracting(e -> ((BusinessException) e).getErrorCode())
					.isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
			verify(writer, never()).saveNoAnswer(anyLong(), anyString(), anyString());
		}

		@Test
		@DisplayName("질문이 비었거나 1000자를 넘으면 400이고 호출하지 않는다")
		void blankOrTooLongQuestionIs400WithoutCallingAi() {
			for (String bad : new String[] {null, "", "   ", "가".repeat(1001)}) {
				assertThatThrownBy(() -> service.ask(me, SESSION_ID, bad, List.of(33L)))
						.isInstanceOf(BusinessException.class)
						.extracting(e -> ((BusinessException) e).getErrorCode())
						.isEqualTo(ErrorCode.VALIDATION_ERROR);
			}
			verify(aiClient, never()).chat(anyString(), anyList(), anyInt());
		}

		@Test
		@DisplayName("질문은 1000자까지 받는다")
		void accepts1000CharQuestion() {
			service.ask(me, SESSION_ID, "가".repeat(1000), List.of());

			verify(writer).saveNoAnswer(eq(SESSION_ID), anyString(), anyString());
		}
	}

	@Nested
	@DisplayName("근거 재확인")
	class SourceCheck {

		@Test
		@DisplayName("통과한 답변만 저장하고 돌려준다")
		void savesAndReturnsOnlyPassedAnswer() {
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
		@DisplayName("sourceVersionIds가 하나라도 scope 밖이면 답변을 버린다")
		void discardsAnswerWhenSourceVersionIdOutOfScope() {
			when(aiClient.chat(anyString(), anyList(), anyInt()))
					.thenReturn(answer(List.of(33L, 52L), List.of(chunk(33L))));

			ChatAnswer answer = service.ask(me, SESSION_ID, QUESTION, List.of(33L));

			assertThat(answer.noAnswer()).isTrue();
			assertThat(answer.tokens()).isEmpty();
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		@DisplayName("근거 조각의 버전이 scope 밖이면 답변을 버린다")
		void discardsAnswerWhenChunkVersionOutOfScope() {
			when(aiClient.chat(anyString(), anyList(), anyInt()))
					.thenReturn(answer(List.of(33L), List.of(chunk(33L), chunk(52L))));

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
		}

		@Test
		@DisplayName("근거가 없는 답변은 버린다")
		void discardsAnswerWithoutSources() {
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenReturn(answer(List.of(), List.of()));

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
		}

		@Test
		@DisplayName("AI Module이 근거 없음이면 근거 없음으로 저장한다")
		void savesNoAnswerWhenAiReturnsNoAnswer() {
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenReturn(AiChat.Result.noAnswerResult());

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		@DisplayName("근거 버전이 그사이 삭제되면 근거 없음이다")
		void noAnswerWhenSourceVersionDeletedMeanwhile() {
			AiChat.Result result = answer(List.of(33L), List.of(chunk(33L)));
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenReturn(result);
			when(writer.saveAnswer(SESSION_ID, QUESTION, result)).thenReturn(Optional.empty());

			assertThat(service.ask(me, SESSION_ID, QUESTION, List.of(33L)).noAnswer()).isTrue();
			verify(writer).saveNoAnswer(SESSION_ID, QUESTION, ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		@DisplayName("AI Module 실패는 502로 올리고 아무것도 저장하지 않는다")
		void aiFailureIs502AndSavesNothing() {
			when(aiClient.chat(anyString(), anyList(), anyInt())).thenThrow(new AiModuleException("down"));

			assertThatThrownBy(() -> service.ask(me, SESSION_ID, QUESTION, List.of(33L)))
					.isInstanceOf(AiModuleException.class);
			verify(writer, never()).saveNoAnswer(anyLong(), anyString(), anyString());
			verify(writer, never()).saveAnswer(anyLong(), anyString(), any());
		}
	}

	@Nested
	@DisplayName("대화 기록 가림")
	class HistoryMasking {

		private Document document(long id) {
			return document(id, me);
		}

		private Document document(long id, AppUser uploadedBy) {
			Document document = Document.builder().name("문서" + id).uploadedBy(uploadedBy).build();
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
		@DisplayName("읽을 수 있는 근거면 그대로 보이고 권한을 잃은 근거나 삭제된 근거가 있으면 가린다")
		void showsReadableSourcesAndMasksRevokedOrDeleted() {
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
		@DisplayName("근거 없음 답변은 가리지 않는다")
		void doesNotMaskNoAnswerMessage() {
			ChatMessage q = message(1L, MessageRole.USER, "질문");
			ChatMessage a = message(2L, MessageRole.ASSISTANT, ChatService.NO_ANSWER_MESSAGE);
			when(messages.findBySessionIdOrderByCreatedAtAscIdAsc(SESSION_ID)).thenReturn(List.of(q, a));
			when(sources.findByMessageIdIn(List.of(2L))).thenReturn(List.of());

			List<ChatMessageResponse> history = service.listMessages(me, SESSION_ID, doc -> false);

			assertThat(history.get(1).masked()).isFalse();
			assertThat(history.get(1).content()).isEqualTo(ChatService.NO_ANSWER_MESSAGE);
		}

		@Test
		@DisplayName("같은 문서의 권한은 한 번만 판정한다")
		void checksEachDocumentOnce() {
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
		@DisplayName("남의 대화는 404다")
		void othersSessionIs404() {
			assertThatThrownBy(() -> service.listMessages(me, 999L, doc -> true))
					.isInstanceOf(ChatSessionNotFoundException.class);
			verify(messages, times(0)).findBySessionIdOrderByCreatedAtAscIdAsc(anyLong());
		}

		@Test
		@DisplayName("내가 등록한 문서와 초대를 수락한 문서의 근거는 보이고 그 밖의 문서 근거는 가린다")
		void showsOwnedAndAcceptedDocumentsAndMasksOthers() {
			AppUser lead = AppUser.builder().email("lead@example.com").passwordHash("x").name("팀장").rankLevel(2).build();
			ReflectionTestUtils.setField(lead, "id", 5L);
			Document owned = document(7L);
			Document invited = document(8L, lead);
			Document notInvited = document(9L, lead);
			ChatMessage a1 = message(2L, MessageRole.ASSISTANT, "답변1");
			ChatMessage a2 = message(4L, MessageRole.ASSISTANT, "답변2");
			ChatMessage a3 = message(6L, MessageRole.ASSISTANT, "답변3");
			when(messages.findBySessionIdOrderByCreatedAtAscIdAsc(SESSION_ID)).thenReturn(List.of(a1, a2, a3));
			when(sources.findByMessageIdIn(List.of(2L, 4L, 6L))).thenReturn(List.of(
					source(a1, version(owned)), source(a2, version(invited)), source(a3, version(notInvited))));
			when(permissions.findAccessibleDocumentIds(3L)).thenReturn(List.of(8L));

			List<ChatMessageResponse> history = service.listMessages(me, SESSION_ID);

			assertThat(history).extracting(ChatMessageResponse::masked).containsExactly(false, false, true);
			assertThat(history.get(1).sources()).singleElement()
					.satisfies(s -> assertThat(s.documentId()).isEqualTo(8L));
		}

		@Test
		@DisplayName("남의 대화면 초대 문서를 조회하지 않고 404다")
		void othersSessionIs404BeforeLoadingPermissions() {
			assertThatThrownBy(() -> service.listMessages(me, 999L))
					.isInstanceOf(ChatSessionNotFoundException.class);
			verify(permissions, never()).findAccessibleDocumentIds(anyLong());
		}
	}
}
