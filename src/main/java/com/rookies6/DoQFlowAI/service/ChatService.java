package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.ai.AiChat;
import com.rookies6.DoQFlowAI.dto.response.ChatMessageResponse;
import com.rookies6.DoQFlowAI.dto.response.ChatSessionResponse;
import com.rookies6.DoQFlowAI.dto.response.ChatSourceResponse;
import com.rookies6.DoQFlowAI.entity.AppUser;
import com.rookies6.DoQFlowAI.entity.ChatMessage;
import com.rookies6.DoQFlowAI.entity.ChatSession;
import com.rookies6.DoQFlowAI.entity.Document;
import com.rookies6.DoQFlowAI.entity.MessageRole;
import com.rookies6.DoQFlowAI.entity.MessageSource;
import com.rookies6.DoQFlowAI.exception.BusinessException;
import com.rookies6.DoQFlowAI.exception.ChatSessionNotFoundException;
import com.rookies6.DoQFlowAI.exception.ErrorCode;
import com.rookies6.DoQFlowAI.repository.ChatMessageRepository;
import com.rookies6.DoQFlowAI.repository.ChatSessionRepository;
import com.rookies6.DoQFlowAI.repository.DocumentPermissionRepository;
import com.rookies6.DoQFlowAI.repository.MessageSourceRepository;
import com.rookies6.DoQFlowAI.security.AccessPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대화 생성·기록 조회·질의응답 < API 25, 26, 27 >
 *
 * 질의응답 순서
 *   1. 질문 검증 (1~1000자), 내 대화인지 확인
 *   2. scope가 비면 AI Module을 호출하지 않고 근거 없음으로 끝낸다
 *   3. POST /internal/chat. 토큰은 끝까지 받아 모아 둔다
 *   4. sourceVersionIds·근거 조각의 버전이 모두 scope 안인지 다시 대조한다. 하나라도 밖이면 답변을 버리고 근거 없음
 *   5. 통과한 답변만 저장하고 돌려준다
 * 근거 없음 문구는 권한 없음·문서 없음·검색 실패·재확인 실패 모두 같다. 응답만으로 문서의 존재를 알 수 없어야 한다.
 *
 * scope는 AccessPolicy.accessibleVersionIds(me)로 구한다 (존재하고, 읽을 수 있고, READY인 문서의 최신 버전).
 */
@Slf4j
@Service
public class ChatService {

	// 사용자에게 보이는 근거 없음 문구 (시스템 아키텍처 설계서 5.1). 문구는 팀 확정 전 임시값
	public static final String NO_ANSWER_MESSAGE = "문서에서 답을 찾지 못했습니다.";

	private final ChatSessionRepository sessions;
	private final ChatMessageRepository messages;
	private final MessageSourceRepository sources;
	private final ChatHistoryWriter writer;
	private final AiClientService aiClient;
	private final AccessPolicy accessPolicy;
	private final DocumentPermissionRepository permissions;
	private final int maxQuestionLength;
	private final int topK;

	public ChatService(ChatSessionRepository sessions, ChatMessageRepository messages,
			MessageSourceRepository sources, ChatHistoryWriter writer, AiClientService aiClient,
			AccessPolicy accessPolicy, DocumentPermissionRepository permissions,
			@Value("${app.chat.max-question-length:1000}") int maxQuestionLength,
			@Value("${app.chat.top-k:5}") int topK) {
		this.sessions = sessions;
		this.messages = messages;
		this.sources = sources;
		this.writer = writer;
		this.aiClient = aiClient;
		this.accessPolicy = accessPolicy;
		this.permissions = permissions;
		this.maxQuestionLength = maxQuestionLength;
		this.topK = topK;
	}

	/** 대화 생성 < API 25 >. 제목은 첫 질문에서 채운다 */
	@Transactional
	public ChatSessionResponse createSession(AppUser me) {
		ChatSession session = sessions.save(ChatSession.builder().user(me).build());
		return ChatSessionResponse.from(session);
	}

	/**
	 * 대화 기록 조회 < API 26 > - 컨트롤러가 부른다
	 *
	 * 읽기 판정은 AccessPolicy.readOrNotFound와 같다: 내가 등록한 문서이거나 초대를 수락(ACCEPTED)한 문서.
	 * 문서 상태(PROCESSING·FAILED)는 보지 않는다. 읽을 수 있는 문서의 과거 답변이 재업로드 중에 가려지면 안 된다.
	 * readOrNotFound를 문서마다 부르고 예외를 잡지 않는 이유: 이 트랜잭션 안에서 예외가 나면 rollback-only가 되어 조회 전체가 실패한다.
	 * 초대 수락 문서는 한 번에 조회한다. 남의 대화면 조회 전에 404로 끝난다.
	 */
	@Transactional(readOnly = true)
	public List<ChatMessageResponse> listMessages(AppUser me, Long sessionId) {
		ChatSession session = ownSession(me, sessionId);
		Long userId = me.getId();
		Set<Long> invited = new HashSet<>(permissions.findAccessibleDocumentIds(userId));
		return listMessages(session, document -> accessPolicy.isOwner(document, userId)
				|| invited.contains(document.getId()));
	}

	/**
	 * 대화 기록 조회 < API 26 >
	 * 근거마다 지금 읽을 수 있는지 다시 판정한다. 근거가 하나라도 가림 대상이면 그 답변 전체를 가린다 (DB 설계서 4.6).
	 *   근거 버전이 삭제됨(version NULL) → 가림 (fail-closed)
	 *   그 버전의 문서를 읽을 수 없음(회수·거절) → 가림
	 *
	 * @param canRead 문서 읽기 권한 판정
	 */
	@Transactional(readOnly = true)
	public List<ChatMessageResponse> listMessages(AppUser me, Long sessionId, Predicate<Document> canRead) {
		return listMessages(ownSession(me, sessionId), canRead);
	}

	private List<ChatMessageResponse> listMessages(ChatSession session, Predicate<Document> canRead) {
		List<ChatMessage> history = messages.findBySessionIdOrderByCreatedAtAscIdAsc(session.getId());

		List<Long> answerIds = history.stream()
				.filter(m -> m.getRole() == MessageRole.ASSISTANT).map(ChatMessage::getId).toList();
		Map<Long, List<MessageSource>> sourcesByAnswer = answerIds.isEmpty() ? Map.of()
				: sources.findByMessageIdIn(answerIds).stream()
						.collect(Collectors.groupingBy(s -> s.getMessage().getId()));

		// 같은 문서의 권한을 근거마다 다시 묻지 않는다
		Map<Long, Boolean> readable = new HashMap<>();
		List<ChatMessageResponse> result = new ArrayList<>(history.size());
		for (ChatMessage message : history) {
			if (message.getRole() == MessageRole.USER) {
				result.add(ChatMessageResponse.question(message));
				continue;
			}
			List<MessageSource> answerSources = sourcesByAnswer.getOrDefault(message.getId(), List.of());
			boolean masked = answerSources.stream().anyMatch(s -> isHidden(s, canRead, readable));
			result.add(masked ? ChatMessageResponse.maskedAnswer(message)
					: ChatMessageResponse.answer(message,
							answerSources.stream().map(ChatSourceResponse::from).toList()));
		}
		return result;
	}

	private static boolean isHidden(MessageSource source, Predicate<Document> canRead, Map<Long, Boolean> readable) {
		if (source.getVersion() == null) {
			return true;
		}
		Document document = source.getVersion().getDocument();
		return !readable.computeIfAbsent(document.getId(), id -> canRead.test(document));
	}

	/**
	 * 질의응답 < API 27 > - 읽을 수 있는 문서 안에서만 답한다
	 * 질문·대화를 먼저 검증한 뒤 scope를 계산한다. 잘못된 요청이면 문서 조회조차 하지 않는다.
	 */
	public ChatAnswer ask(AppUser me, Long sessionId, String question) {
		checkQuestion(question);
		ownSession(me, sessionId);
		return ask(me, sessionId, question, accessPolicy.accessibleVersionIds(me.getId()));
	}

	/**
	 * 질의응답 < API 27 >
	 * AI Module 호출이 길어질 수 있어 이 메서드는 트랜잭션을 잡지 않는다. 저장은 ChatHistoryWriter가 따로 한다.
	 * AI Module 실패(4xx·5xx·타임아웃·error 이벤트)는 AiModuleException(502)으로 올라가고 아무것도 저장하지 않는다.
	 *
	 * @param scope 검색해도 되는 버전 ID. AccessPolicy.accessibleVersionIds(me)
	 */
	public ChatAnswer ask(AppUser me, Long sessionId, String question, Collection<Long> scope) {
		String checked = checkQuestion(question);
		ownSession(me, sessionId);

		Set<Long> allowed = scope == null ? Set.of() : new TreeSet<>(scope);
		if (allowed.isEmpty()) {
			// 검색할 문서가 없으면 LLM을 부르지 않는다 (Backend 개발 명세서 6)
			log.info("chat scope is empty; AI Module not called sessionId={}", sessionId);
			return writer.saveNoAnswer(sessionId, checked, NO_ANSWER_MESSAGE);
		}

		AiChat.Result result = aiClient.chat(checked, List.copyOf(allowed), topK);
		if (result.noAnswer() || result.answer().isBlank()) {
			return writer.saveNoAnswer(sessionId, checked, NO_ANSWER_MESSAGE);
		}
		if (!withinScope(result, allowed)) {
			// TODO AuditService 병합 후 SOURCE_CHECK_FAILED 기록. 답변·조각 내용은 남기지 않는다
			log.warn("SOURCE_CHECK_FAILED sessionId={} scopeSize={} sourceVersionIds={}",
					sessionId, allowed.size(), result.sourceVersionIds());
			return writer.saveNoAnswer(sessionId, checked, NO_ANSWER_MESSAGE);
		}
		return writer.saveAnswer(sessionId, checked, result)
				// 근거 버전이 그사이 삭제됨. 출처를 보여줄 수 없는 답변은 내보내지 않는다
				.orElseGet(() -> writer.saveNoAnswer(sessionId, checked, NO_ANSWER_MESSAGE));
	}

	/**
	 * 근거 재확인. 근거가 하나도 없거나 하나라도 scope 밖이면 실패다 (fail-closed)
	 * 근거 없는 답변을 저장하면 나중에 권한이 회수돼도 가릴 기준이 없으므로 통과시키지 않는다.
	 */
	static boolean withinScope(AiChat.Result result, Set<Long> allowed) {
		if (result.sourceVersionIds().isEmpty() || result.chunks().isEmpty()) {
			return false;
		}
		boolean idsInScope = result.sourceVersionIds().stream().allMatch(id -> id != null && allowed.contains(id));
		boolean chunksInScope = result.chunks().stream()
				.allMatch(c -> c.versionId() != null && allowed.contains(c.versionId()));
		return idsInScope && chunksInScope;
	}

	private String checkQuestion(String question) {
		if (question == null || question.isBlank()
				|| question.codePointCount(0, question.length()) > maxQuestionLength) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "invalid chat question length");
		}
		return question.strip();
	}

	private ChatSession ownSession(AppUser me, Long sessionId) {
		return sessions.findByIdAndUserId(sessionId, me.getId())
				.orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
	}
}
