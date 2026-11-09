package com.rookies6.DoQFlowAI.service;

import com.rookies6.DoQFlowAI.dto.ai.AiChat;
import com.rookies6.DoQFlowAI.dto.response.ChatSourceResponse;
import com.rookies6.DoQFlowAI.entity.ChatMessage;
import com.rookies6.DoQFlowAI.entity.ChatSession;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문·답변·근거 저장 < API 27 >
 *
 * AI Module 호출(최대 60초) 동안 DB 트랜잭션을 잡고 있지 않도록, 호출이 끝난 뒤 저장만 짧은 트랜잭션으로 따로 한다.
 * ChatService 안에서 부르면 @Transactional이 걸리지 않으므로 별도 bean으로 둔다.
 */
@Component
public class ChatHistoryWriter {

	// 대화 제목 길이 (첫 질문 앞부분, DB 설계서 4.4)
	static final int TITLE_MAX_CODE_POINTS = 50;

	private final ChatSessionRepository sessions;
	private final ChatMessageRepository messages;
	private final MessageSourceRepository sources;
	private final DocumentVersionRepository versions;

	public ChatHistoryWriter(ChatSessionRepository sessions, ChatMessageRepository messages,
			MessageSourceRepository sources, DocumentVersionRepository versions) {
		this.sessions = sessions;
		this.messages = messages;
		this.sources = sources;
		this.versions = versions;
	}

	/** 질문과 근거 없음 문구를 저장한다. 근거는 저장하지 않는다 */
	@Transactional
	public ChatAnswer saveNoAnswer(Long sessionId, String question, String noAnswerMessage) {
		ChatSession session = saveQuestion(sessionId, question);
		ChatMessage answer = messages.save(ChatMessage.builder()
				.session(session).role(MessageRole.ASSISTANT).content(noAnswerMessage).build());
		return ChatAnswer.noAnswer(answer.getId());
	}

	/**
	 * 근거 재확인을 통과한 답변과 근거를 저장한다
	 * 문서 이름·버전 번호는 지금 값을 스냅샷으로 남긴다. 나중에 이름을 바꿔도 답변 당시 값이 보인다.
	 *
	 * @return 근거 버전이 그사이 삭제되었으면 빈 값. 이때는 아무것도 저장하지 않는다
	 */
	@Transactional
	public Optional<ChatAnswer> saveAnswer(Long sessionId, String question, AiChat.Result result) {
		List<Long> chunkVersionIds = result.chunks().stream().map(AiChat.Chunk::versionId).distinct().toList();
		Map<Long, DocumentVersion> byId = versions.findAllById(chunkVersionIds).stream()
				.collect(Collectors.toMap(DocumentVersion::getId, Function.identity()));
		if (byId.size() != chunkVersionIds.size()) {
			return Optional.empty();
		}

		ChatSession session = saveQuestion(sessionId, question);
		ChatMessage answer = messages.save(ChatMessage.builder()
				.session(session).role(MessageRole.ASSISTANT).content(result.answer()).build());

		List<ChatSourceResponse> saved = new ArrayList<>();
		for (AiChat.Chunk chunk : result.chunks()) {
			DocumentVersion version = byId.get(chunk.versionId());
			MessageSource source = sources.save(MessageSource.builder()
					.message(answer)
					.document(version.getDocument())
					.version(version)
					.documentName(version.getDocument().getName())
					.versionNo(version.getVersionNo())
					.pageNumber(chunk.pageNumber())
					.chunkText(chunk.chunkText() == null ? "" : chunk.chunkText())
					.build());
			saved.add(ChatSourceResponse.from(source));
		}
		return Optional.of(new ChatAnswer(answer.getId(), false, result.tokens(), List.copyOf(saved)));
	}

	/** 질문 저장. 대화 제목이 없으면 이 질문 앞부분으로 채운다 */
	private ChatSession saveQuestion(Long sessionId, String question) {
		// 질문 처리 중 로그아웃으로 대화가 지워졌으면 저장하지 않는다
		ChatSession session = sessions.findById(sessionId)
				.orElseThrow(() -> new ChatSessionNotFoundException(sessionId));
		session.titleIfAbsent(title(question));
		messages.save(ChatMessage.builder().session(session).role(MessageRole.USER).content(question).build());
		return session;
	}

	static String title(String question) {
		String oneLine = question.strip().replaceAll("\\s+", " ");
		if (oneLine.codePointCount(0, oneLine.length()) <= TITLE_MAX_CODE_POINTS) {
			return oneLine;
		}
		return oneLine.substring(0, oneLine.offsetByCodePoints(0, TITLE_MAX_CODE_POINTS));
	}
}
