package com.smarthome.assistant;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.assistant.AssistantDtos.AnswerStatus;
import com.smarthome.assistant.AssistantDtos.ChatResponse;
import com.smarthome.assistant.AssistantDtos.ConversationDto;
import com.smarthome.assistant.AssistantDtos.DocumentDto;
import com.smarthome.assistant.AssistantDtos.IndexInfo;
import com.smarthome.assistant.AssistantDtos.LimitsInfo;
import com.smarthome.assistant.AssistantDtos.MessageDto;
import com.smarthome.assistant.AssistantDtos.ModelInfo;
import com.smarthome.assistant.AssistantDtos.SourceDto;
import com.smarthome.assistant.AssistantDtos.StatusResponse;
import com.smarthome.assistant.AssistantDtos.SuggestionDto;
import com.smarthome.assistant.AssistantExceptions.AssistantUnavailableException;
import com.smarthome.assistant.AssistantExceptions.ConversationNotFoundException;
import com.smarthome.assistant.AssistantExceptions.DocumentNotFoundException;
import com.smarthome.assistant.AssistantExceptions.InvalidChatRequestException;
import com.smarthome.assistant.AssistantExceptions.SuggestionNotFoundException;
import com.smarthome.assistant.AssistantExceptions.SuggestionNotPendingException;
import com.smarthome.assistant.AssistantExceptions.TooManyChatRequestsException;
import com.smarthome.assistant.LlmClient.Message;
import com.smarthome.assistant.PromptBuilder.Prompt;
import com.smarthome.assistant.PromptBuilder.Source;
import com.smarthome.assistant.RagChunkStore.Hit;
import com.smarthome.automation.AutomationDtos.ConditionRequest;
import com.smarthome.automation.AutomationDtos.RuleRequest;
import com.smarthome.automation.AutomationDtos.RuleResponse;
import com.smarthome.automation.AutomationExceptions.InvalidRuleException;
import com.smarthome.automation.AutomationService;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceExceptions.DeviceNotFoundException;
import com.smarthome.device.DeviceService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The chat flow: validate, retrieve (owner-scoped), ask the model with the retrieved text as data, check the answer
 * against the sources, store it. Whatever happens, the user gets an honest message: references only when no model is
 * available, "nothing found" when retrieval is empty, and a 503 (nothing changed) if the database is down.
 *
 * Nothing here can reach a device: the only write paths are the chat tables, the vector store and, after an explicit
 * approval by the owner, {@link AutomationService#create} (which applies all the normal checks, including the gas-LED rule).
 */
@Service
public class AssistantService {

	private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
	private static final String NOTICE = "The assistant explains your own data and suggests rules. It cannot control devices, and a suggested rule "
			+ "only exists after you approve it. It can be wrong: check the sources. It is not a safety system; the device's own gas warning is independent of it.";

	private final AssistantProperties props;
	private final LlmClient llm;
	private final EmbeddingClient embedder;
	private final RagIndexer indexer;
	private final RagChunkStore store;
	private final PromptBuilder prompts;
	private final SuggestionParser parser;
	private final ChatConversationRepository conversations;
	private final ChatMessageRepository messages;
	private final AssistantSuggestionRepository suggestions;
	private final AutomationService automation;
	private final DeviceService deviceService;
	private final ObjectMapper mapper;
	private final ChatRateLimiter limiter;
	private final Clock clock;

	public AssistantService(AssistantProperties props, LlmClient llm, EmbeddingClient embedder, RagIndexer indexer, RagChunkStore store,
			PromptBuilder prompts, SuggestionParser parser, ChatConversationRepository conversations, ChatMessageRepository messages,
			AssistantSuggestionRepository suggestions, AutomationService automation, DeviceService deviceService, ObjectMapper mapper,
			ChatRateLimiter limiter, Clock clock) {
		this.limiter = limiter;
		this.props = props;
		this.llm = llm;
		this.embedder = embedder;
		this.indexer = indexer;
		this.store = store;
		this.prompts = prompts;
		this.parser = parser;
		this.conversations = conversations;
		this.messages = messages;
		this.suggestions = suggestions;
		this.automation = automation;
		this.deviceService = deviceService;
		this.mapper = mapper;
		this.clock = clock;
	}

	// ---- chat ----------------------------------------------------------------------------------

	public ChatResponse chat(UUID ownerId, UUID conversationId, String rawMessage) {
		int max = props.chat().maxMessageChars();
		if (rawMessage == null || rawMessage.isBlank()) {
			throw new InvalidChatRequestException("message", "Type a question first");
		}
		if (rawMessage.length() > max) {
			throw new InvalidChatRequestException("message", "At most " + max + " characters");
		}
		String question = Text.oneLine(rawMessage, max);
		if (question.isEmpty()) {
			throw new InvalidChatRequestException("message", "Type a question first");
		}
		limiter.check(ownerId);
		Instant now = clock.instant();

		ChatConversation conv;
		List<Message> history;
		ChatMessage userMsg;
		try {
			conv = conversationId == null ? newConversation(ownerId, question, now) : ownedConversation(ownerId, conversationId);
			history = history(conv.getId());
			userMsg = messages.save(new ChatMessage(UUID.randomUUID(), conv.getId(), "user", question, null, null, null, now));
		} catch (DataAccessException e) {
			throw unavailable(e);
		}

		Answer a = answer(ownerId, question, history);

		UUID assistantId = UUID.randomUUID();
		List<SuggestionDto> created = new ArrayList<>();
		List<String> warnings = new ArrayList<>(a.warnings);
		try {
			if (a.proposal != null) {
				SuggestionDto s = storeProposal(ownerId, conv.getId(), assistantId, a.proposal, warnings);
				if (s != null) {
					created.add(s);
				}
			}
			ChatMessage assistantMsg = messages.save(new ChatMessage(assistantId, conv.getId(), "assistant", cut(a.text, 8000), a.status.name(),
					sourcesJson(a.sources), warnings.isEmpty() ? null : warningsJson(warnings), clock.instant()));
			conv.touch(clock.instant());
			conversations.save(conv);
			return new ChatResponse(conv.getId(), conv.getTitle(), toDto(userMsg, List.of()), toDto(assistantMsg, created));
		} catch (DataAccessException e) {
			throw unavailable(e);
		}
	}

	/** What the pipeline produced, before anything is stored. */
	private record Answer(AnswerStatus status, String text, List<SourceDto> sources, List<String> warnings, SuggestionParser.Proposal proposal) {
	}

	private Answer answer(UUID ownerId, String question, List<Message> history) {
		List<Hit> hits;
		try {
			indexer.ensureKnowledge();
			indexer.refreshLive(ownerId);
			float[] q = embedder.embedOne(question);
			int topK = props.rag().topK();
			List<Hit> candidates = new ArrayList<>(store.search(ownerId, q, embedder.modelId(), topK * 4, props.rag().minScore()));
			boolean readings = asksAboutReadings(question);
			if (readings) {
				// The owner's per-device status chunks hold the latest stored values; make sure they are candidates even
				// when their word-overlap score is below the cut-off (they list many fields, which dilutes the score).
				Set<UUID> seen = candidates.stream().map(Hit::id).collect(Collectors.toSet());
				for (Hit h : store.ownedOfType(ownerId, LiveFacts.DEVICE_STATUS, q, embedder.modelId(), MAX_STATUS_CHUNKS)) {
					if (h.deviceUuid() != null && seen.add(h.id())) {
						candidates.add(h);
					}
				}
			}
			hits = rerank(question, names(ownerId), candidates, topK, readings);
		} catch (LlmUnavailableException e) {
			return new Answer(AnswerStatus.RETRIEVAL_UNAVAILABLE, "I could not search your data right now: " + e.getMessage()
					+ " Nothing was guessed. Please try again later.", List.of(), List.of(), null);
		} catch (DataAccessException e) {
			throw unavailable(e);
		}
		// Second line of defence behind the owner filter in the search itself.
		List<Hit> mine = new ArrayList<>();
		for (Hit h : hits) {
			if (h.ownerUserId() != null && !h.ownerUserId().equals(ownerId)) {
				log.error("Dropped a retrieved chunk that belongs to another user (this must never happen)");
				continue;
			}
			mine.add(h);
		}
		if (mine.isEmpty()) {
			return new Answer(AnswerStatus.NO_CONTEXT, "I could not find anything about that in your devices, readings, anomalies, automation rules "
					+ "or the help text, so I will not guess. Try naming a device, a sensor or a rule, or add a document under Knowledge.", List.of(), List.of(), null);
		}

		Prompt prompt = prompts.build(question, history, mine, props.rag().maxContextChars());
		List<SourceDto> uncited = toSources(prompt.sources(), List.of());
		if (!llm.configured()) {
			return new Answer(AnswerStatus.NOT_CONFIGURED, "No AI language model is set up (" + llm.notConfiguredReason().trim()
					+ "). Here are the most relevant records I found; they are real stored data, not a generated answer.", uncited, List.of(), null);
		}

		String reply;
		try {
			reply = llm.complete(prompt.system(), prompt.messages());
		} catch (LlmUnavailableException e) {
			return new Answer(AnswerStatus.LLM_UNAVAILABLE, "The AI model could not answer: " + e.getMessage()
					+ " Here are the most relevant records I found.", uncited, List.of(), null);
		} catch (RuntimeException e) {
			log.warn("Language model client failed unexpectedly: {}", e.getClass().getSimpleName());
			return new Answer(AnswerStatus.LLM_UNAVAILABLE, "The AI model could not answer because of an unexpected error. Here are the most relevant records I found.",
					uncited, List.of(), null);
		}

		SuggestionParser.Parsed parsed = parser.parse(reply);
		String visible = parsed.visibleText();
		if (visible.isBlank()) {
			visible = parsed.proposal() != null ? "I prepared a rule suggestion below. It does nothing until you approve it." : "The AI model gave no answer.";
		}
		GroundingCheck.Result g = GroundingCheck.check(visible, prompt.sources().size(), prompt.contextText(), question);
		List<String> warnings = new ArrayList<>(g.warnings());
		if (parsed.problem() != null) {
			warnings.add("Rule suggestion: " + parsed.problem() + ".");
		}
		return new Answer(AnswerStatus.ANSWERED, g.text(), toSources(prompt.sources(), g.cited()), warnings, parsed.proposal());
	}

	/**
	 * Hybrid ranking over a wider candidate pool: vector similarity plus a small boost for chunks about a device the
	 * question names (and a little more for that device's status, the canonical source for "now" questions). Word-level
	 * embeddings rank many chunks that repeat a device name almost equally, and the person clearly meant that device.
	 * The boost only reorders chunks the owner-scoped search already returned; it can never add anything.
	 */
	static List<Hit> rerank(String question, Map<UUID, String> deviceNames, List<Hit> candidates, int topK) {
		return rerank(question, deviceNames, candidates, topK, false);
	}

	/**
	 * With {@code readings} (a question about sensor values), the devices' status chunks come first: they are the
	 * record of the latest stored reading, which such a question is about. Still only chunks the owner-scoped searches
	 * returned.
	 */
	static List<Hit> rerank(String question, Map<UUID, String> deviceNames, List<Hit> candidates, int topK, boolean readings) {
		String q = question.toLowerCase(java.util.Locale.ROOT);
		Set<UUID> mentioned = new java.util.HashSet<>();
		deviceNames.forEach((id, name) -> {
			if (name.length() >= 3 && q.contains(name.toLowerCase(java.util.Locale.ROOT))) {
				mentioned.add(id);
			}
		});
		java.util.Comparator<Hit> byScore = java.util.Comparator.comparingDouble((Hit h) -> -(h.score() + boost(h, mentioned)));
		java.util.Comparator<Hit> order = readings
				? java.util.Comparator.comparingInt((Hit h) -> isDeviceStatus(h) ? 0 : 1).thenComparing(byScore)
				: byScore;
		return candidates.stream().sorted(order).limit(topK).toList();
	}

	private static boolean isDeviceStatus(Hit h) {
		return LiveFacts.DEVICE_STATUS.equals(h.sourceType()) && h.deviceUuid() != null;
	}

	private static final int MAX_STATUS_CHUNKS = 3;
	private static final java.util.regex.Pattern READING_WORDS = java.util.regex.Pattern.compile(
			"\\b(temperature|temp|degrees?|celsius|hot|cold|warm|humidity|humid|gas|smoke|mq-?2|motion|movement|pir|readings?|sensors?|values?)\\b");

	/** True for questions about sensor values ("what is the current temperature", "explain the latest gas readings"). */
	static boolean asksAboutReadings(String question) {
		return READING_WORDS.matcher(question.toLowerCase(java.util.Locale.ROOT)).find();
	}

	private static double boost(Hit h, Set<UUID> mentioned) {
		if (h.deviceUuid() == null || !mentioned.contains(h.deviceUuid())) {
			return 0;
		}
		return "DEVICE_STATUS".equals(h.sourceType()) ? 0.2 : 0.15;
	}

	private SuggestionDto storeProposal(UUID ownerId, UUID conversationId, UUID messageId, SuggestionParser.Proposal p, List<String> warnings) {
		try {
			automation.check(ownerId, p.rule());                      // fields, ownership of every device, gas-LED rule, rule limit
		} catch (InvalidRuleException e) {
			warnings.add("A rule was proposed but discarded because it is not valid: " + e.fieldErrors().values().stream().findFirst().orElse("invalid rule") + ".");
			return null;
		} catch (DeviceNotFoundException e) {
			warnings.add("A rule was proposed but discarded because it names a device that is not one of yours.");
			return null;
		}
		if (suggestions.countByOwnerUserIdAndStatus(ownerId, AssistantSuggestion.PENDING) >= 20) {
			warnings.add("A rule was proposed but not kept: you already have 20 suggestions waiting. Approve or reject some first.");
			return null;
		}
		Instant now = clock.instant();
		AssistantSuggestion s = new AssistantSuggestion(UUID.randomUUID(), ownerId, conversationId, messageId, p.summary(), p.rationale(),
				json(p.rule()), now, now.plus(props.chat().suggestionTtlMinutes(), ChronoUnit.MINUTES));
		return toDto(suggestions.save(s), names(ownerId));
	}

	// ---- suggestions ---------------------------------------------------------------------------

	@Transactional
	public List<SuggestionDto> pendingSuggestions(UUID ownerId) {
		return guard(() -> {
			suggestions.expireOld(ownerId, clock.instant());
			Map<UUID, String> names = names(ownerId);
			return suggestions.findByOwnerUserIdAndStatusOrderByCreatedAtDesc(ownerId, AssistantSuggestion.PENDING, PageRequest.of(0, 50))
					.stream().map(s -> toDto(s, names)).toList();
		});
	}

	/**
	 * The owner's explicit approval. Creates the rule through {@link AutomationService#create}, which re-runs every check
	 * (including that the user still owns the devices and that the gas LED is never switched off) and then stores it.
	 */
	@Transactional
	public RuleResponse approve(UUID ownerId, UUID suggestionId) {
		AssistantSuggestion s = suggestions.findByIdAndOwnerUserId(suggestionId, ownerId).orElseThrow(SuggestionNotFoundException::new);
		Instant now = clock.instant();
		if (suggestions.claim(s.getId(), ownerId, AssistantSuggestion.APPROVED, now) == 0) {
			throw new SuggestionNotPendingException(notPendingMessage(s, now));
		}
		RuleRequest req;
		try {
			req = mapper.readValue(s.getRuleJson(), RuleRequest.class);
		} catch (RuntimeException e) {
			throw new SuggestionNotPendingException("This suggestion can no longer be read");
		}
		RuleResponse rule = automation.create(ownerId, req);     // throws (and rolls the approval back) if anything is no longer valid
		s.decide(AssistantSuggestion.APPROVED, now, rule.id());
		return rule;
	}

	@Transactional
	public SuggestionDto reject(UUID ownerId, UUID suggestionId) {
		AssistantSuggestion s = suggestions.findByIdAndOwnerUserId(suggestionId, ownerId).orElseThrow(SuggestionNotFoundException::new);
		Instant now = clock.instant();
		if (suggestions.claim(s.getId(), ownerId, AssistantSuggestion.REJECTED, now) == 0) {
			throw new SuggestionNotPendingException(notPendingMessage(s, now));
		}
		s.decide(AssistantSuggestion.REJECTED, now, null);
		return toDto(s, names(ownerId));
	}

	private static String notPendingMessage(AssistantSuggestion s, Instant now) {
		if (AssistantSuggestion.PENDING.equals(s.getStatus()) && s.getExpiresAt().isBefore(now)) {
			return "This suggestion has expired. Ask the assistant again.";
		}
		return "This suggestion was already " + s.getStatus().toLowerCase() + ".";
	}

	// ---- conversations -------------------------------------------------------------------------

	public List<ConversationDto> conversations(UUID ownerId) {
		return guard(() -> conversations.findByOwnerUserIdOrderByUpdatedAtDesc(ownerId, PageRequest.of(0, 100)).stream()
				.map(c -> new ConversationDto(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt())).toList());
	}

	public List<MessageDto> messages(UUID ownerId, UUID conversationId) {
		return guard(() -> {
			ChatConversation c = ownedConversation(ownerId, conversationId);
			List<ChatMessage> list = messages.findByConversationIdOrderByCreatedAtAscIdAsc(c.getId(), PageRequest.of(0, 500));
			Set<UUID> ids = list.stream().map(ChatMessage::getId).collect(Collectors.toSet());
			Map<UUID, String> names = names(ownerId);
			Map<UUID, List<SuggestionDto>> byMessage = new HashMap<>();
			if (!ids.isEmpty()) {
				for (AssistantSuggestion s : suggestions.findByMessageIdIn(ids)) {
					if (s.getOwnerUserId().equals(ownerId)) {
						byMessage.computeIfAbsent(s.getMessageId(), k -> new ArrayList<>()).add(toDto(s, names));
					}
				}
			}
			return list.stream().map(m -> toDto(m, byMessage.getOrDefault(m.getId(), List.of()))).toList();
		});
	}

	@Transactional
	public void deleteConversation(UUID ownerId, UUID conversationId) {
		guard(() -> {
			conversations.delete(ownedConversation(ownerId, conversationId));
			return null;
		});
	}

	// ---- documents and status ------------------------------------------------------------------

	public DocumentDto addDocument(UUID ownerId, String title, String text) {
		try {
			RagIndexer.DocumentInfo d = indexer.addDocument(ownerId, title, text);
			return new DocumentDto(d.id(), d.title(), d.addedAt(), d.chunks());
		} catch (LlmUnavailableException e) {
			throw new AssistantUnavailableException("The document was not saved because it could not be indexed: " + e.getMessage());
		} catch (DataAccessException e) {
			throw unavailable(e);
		}
	}

	public List<DocumentDto> documents(UUID ownerId) {
		return guard(() -> indexer.documents(ownerId).stream().map(s -> new DocumentDto(s.sourceKey(), s.title(), s.asOf(), s.chunks())).toList());
	}

	public void deleteDocument(UUID ownerId, String id) {
		if (!guard(() -> indexer.deleteDocument(ownerId, id))) {
			throw new DocumentNotFoundException();
		}
	}

	public StatusResponse status(UUID ownerId) {
		ModelInfo l = new ModelInfo(llm.configured(), llm.provider(), llm.model(), llm.configured() ? "" : llm.notConfiguredReason());
		ModelInfo e = new ModelInfo(true, embedder.local() ? "local" : "openai", embedder.modelId(),
				embedder.local() ? "Built-in word-based embedder: matches wording, not meaning. Set EMBEDDING_PROVIDER=openai for semantic search." : "");
		IndexInfo index = guard(() -> new IndexInfo(store.countOwned(ownerId), store.countShared(), store.sources(ownerId, RagIndexer.DOCUMENT).size()));
		AssistantProperties.Rag r = props.rag();
		return new StatusResponse(l, e, index, new LimitsInfo(props.chat().maxMessageChars(), r.maxDocumentChars(), r.maxDocumentsPerUser(), r.topK()), NOTICE);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private ChatConversation newConversation(UUID ownerId, String firstQuestion, Instant now) {
		if (conversations.countByOwnerUserId(ownerId) >= props.chat().maxConversationsPerUser()) {
			throw new InvalidChatRequestException("conversationId", "You have " + props.chat().maxConversationsPerUser()
					+ " conversations; delete one to start another");
		}
		return conversations.save(new ChatConversation(UUID.randomUUID(), ownerId, Text.oneLine(firstQuestion, 60), now));
	}

	private ChatConversation ownedConversation(UUID ownerId, UUID id) {
		return conversations.findByIdAndOwnerUserId(id, ownerId).orElseThrow(ConversationNotFoundException::new);
	}

	/** Earlier completed question/answer pairs, oldest first. Failed or unanswered turns are not replayed to the model. */
	private List<Message> history(UUID conversationId) {
		List<ChatMessage> latest = new ArrayList<>(messages.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId,
				PageRequest.of(0, Math.max(0, props.chat().historyTurns()) * 4 + 2)));
		Collections.reverse(latest);
		List<Message> out = new ArrayList<>();
		for (int i = 0; i + 1 < latest.size(); i++) {
			ChatMessage u = latest.get(i);
			ChatMessage a = latest.get(i + 1);
			if ("user".equals(u.getRole()) && "assistant".equals(a.getRole()) && AnswerStatus.ANSWERED.name().equals(a.getAnswerStatus())) {
				out.add(prompts.historyTurn("user", u.getContent()));
				out.add(prompts.historyTurn("assistant", a.getContent()));
				i++;
			}
		}
		int keep = Math.max(0, props.chat().historyTurns()) * 2;
		return out.size() > keep ? new ArrayList<>(out.subList(out.size() - keep, out.size())) : out;
	}

	private List<SourceDto> toSources(List<Source> used, List<Integer> cited) {
		List<SourceDto> out = new ArrayList<>();
		for (Source s : used) {
			Hit h = s.hit();
			int n = Integer.parseInt(s.ref().substring(1));
			String body = h.content().startsWith(h.title() + ": ") ? h.content().substring(h.title().length() + 2) : h.content();
			out.add(new SourceDto(s.ref(), h.sourceType(), h.title(), Text.oneLine(body, 280), Math.round(h.score() * 1000) / 1000.0, h.asOf(),
					h.deviceUuid(), cited.contains(n)));
		}
		return out;
	}

	private MessageDto toDto(ChatMessage m, List<SuggestionDto> sugg) {
		List<SourceDto> sources = m.getSourcesJson() == null ? List.of() : read(m.getSourcesJson(), new TypeReference<List<SourceDto>>() { });
		List<String> warnings = m.getWarningsJson() == null ? List.of() : read(m.getWarningsJson(), new TypeReference<List<String>>() { });
		AnswerStatus status = m.getAnswerStatus() == null ? null : AnswerStatus.valueOf(m.getAnswerStatus());
		return new MessageDto(m.getId(), m.getRole(), m.getContent(), status, sources, warnings, sugg, m.getCreatedAt());
	}

	private SuggestionDto toDto(AssistantSuggestion s, Map<UUID, String> names) {
		RuleRequest req = mapper.readValue(s.getRuleJson(), RuleRequest.class);
		return new SuggestionDto(s.getId(), s.getSummary(), s.getRationale(), describe(req, names), req, s.getStatus(), s.getCreatedAt(),
				s.getExpiresAt(), s.getCreatedRuleId());
	}

	/** A plain-words reading of the rule, built from the validated request (not from the model's prose). */
	public static String describe(RuleRequest r, Map<UUID, String> names) {
		StringBuilder sb = new StringBuilder();
		String source = r.deviceId() == null ? "a device" : "\"" + names.getOrDefault(r.deviceId(), "unknown device") + "\"";
		String target = r.targetDeviceId() == null ? source : "\"" + names.getOrDefault(r.targetDeviceId(), "unknown device") + "\"";
		if (r.trigger() != null && "SCHEDULE".equalsIgnoreCase(r.trigger().type())) {
			sb.append("At ").append(r.trigger().time()).append(' ').append(r.trigger().timeZone() == null ? "UTC" : r.trigger().timeZone());
			sb.append(r.trigger().days() == null || r.trigger().days().isEmpty() ? " every day" : " on " + String.join(", ", r.trigger().days()));
		} else {
			sb.append("On every new reading from ").append(source);
		}
		if (r.conditions() != null && !r.conditions().isEmpty()) {
			String logic = "OR".equalsIgnoreCase(r.logic()) ? " OR " : " AND ";
			sb.append(", if ").append(r.conditions().stream().map(AssistantService::cond).collect(Collectors.joining(logic)));
		}
		if (r.action() != null) {
			sb.append(": set ").append(r.action().action().replace("set_", "").replace("_", " ")).append(" to ").append(r.action().value())
					.append(" on ").append(target).append('.');
		}
		return sb.toString();
	}

	private static String cond(ConditionRequest c) {
		return c.sensor() + " " + c.operator() + " " + (c.value() == null ? "?" : c.value().toString());
	}

	private Map<UUID, String> names(UUID ownerId) {
		Map<UUID, String> out = new HashMap<>();
		for (DeviceResponse d : deviceService.list(ownerId)) {
			out.put(d.id(), Text.oneLine(d.name(), 60));
		}
		return out;
	}

	private <T> T guard(Supplier<T> work) {
		try {
			return work.get();
		} catch (DataAccessException e) {
			throw unavailable(e);
		}
	}

	private AssistantUnavailableException unavailable(DataAccessException e) {
		log.error("Assistant database access failed: {}", e.getClass().getSimpleName());
		return new AssistantUnavailableException("The assistant could not reach its data store. Nothing was changed; please try again shortly.");
	}

	private String json(Object o) {
		return mapper.writeValueAsString(o);
	}

	private <T> T read(String json, TypeReference<T> type) {
		try {
			return mapper.readValue(json, type);
		} catch (RuntimeException e) {
			throw new IllegalStateException("Stored chat data could not be read", e);
		}
	}

	private static String cut(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private String warningsJson(List<String> warnings) {
		List<String> w = new ArrayList<>();
		warnings.forEach(x -> w.add(Text.oneLine(x, 300)));
		while (w.size() > 1 && json(w).length() > 1900) {
			w.remove(w.size() - 1);
		}
		return json(w);
	}

	/** The references as JSON that fits the column: snippets are shortened first, then the list, so the stored JSON is always valid. */
	private String sourcesJson(List<SourceDto> sources) {
		String full = json(sources);
		if (full.length() <= 7900) {
			return full;
		}
		List<SourceDto> shorter = new ArrayList<>();
		for (SourceDto s : sources) {
			shorter.add(new SourceDto(s.ref(), s.type(), s.title(), Text.oneLine(s.snippet(), 100), s.score(), s.asOf(), s.deviceId(), s.cited()));
		}
		while (shorter.size() > 1 && json(shorter).length() > 7900) {
			shorter.remove(shorter.size() - 1);
		}
		return json(shorter);
	}
}
