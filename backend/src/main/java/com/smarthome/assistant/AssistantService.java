package com.smarthome.assistant;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.analytics.AnalyticsRepository;
import com.smarthome.analytics.AnalyticsRepository.GasAlertRow;
import com.smarthome.analytics.Visual;
import com.smarthome.assistant.AssistantDtos.ActionDto;
import com.smarthome.assistant.AssistantDtos.AnswerStatus;
import com.smarthome.assistant.AssistantDtos.ChatResponse;
import com.smarthome.assistant.AssistantDtos.ConversationDto;
import com.smarthome.assistant.AssistantDtos.DocumentDto;
import com.smarthome.assistant.AssistantDtos.ExtrasDto;
import com.smarthome.assistant.AssistantDtos.IndexInfo;
import com.smarthome.assistant.AssistantDtos.LimitsInfo;
import com.smarthome.assistant.AssistantDtos.MessageDto;
import com.smarthome.assistant.AssistantDtos.ModelInfo;
import com.smarthome.assistant.AssistantDtos.SourceDto;
import com.smarthome.assistant.AssistantDtos.StatusResponse;
import com.smarthome.assistant.AssistantDtos.SuggestionDto;
import com.smarthome.assistant.AssistantDtos.ToolUseDto;
import com.smarthome.assistant.AssistantExceptions.AssistantUnavailableException;
import com.smarthome.assistant.AssistantExceptions.ConversationNotFoundException;
import com.smarthome.assistant.AssistantExceptions.DocumentNotFoundException;
import com.smarthome.assistant.AssistantExceptions.InvalidChatRequestException;
import com.smarthome.assistant.AssistantExceptions.SuggestionNotFoundException;
import com.smarthome.assistant.AssistantExceptions.SuggestionNotPendingException;
import com.smarthome.assistant.LlmClient.Message;
import com.smarthome.assistant.LlmClient.ToolCall;
import com.smarthome.assistant.LlmClient.Turn;
import com.smarthome.assistant.PromptBuilder.Prompt;
import com.smarthome.assistant.PromptBuilder.Source;
import com.smarthome.assistant.QueryPlanner.Plan;
import com.smarthome.assistant.QueryPlanner.PlannedCall;
import com.smarthome.assistant.RagChunkStore.Hit;
import com.smarthome.assistant.tools.Fmt;
import com.smarthome.assistant.tools.ToolRegistry;
import com.smarthome.assistant.tools.ToolTypes.Focus;
import com.smarthome.assistant.tools.ToolTypes.PendingAction;
import com.smarthome.assistant.tools.ToolTypes.ToolContext;
import com.smarthome.assistant.tools.ToolTypes.ToolResult;
import com.smarthome.automation.AutomationDtos.ConditionRequest;
import com.smarthome.automation.AutomationDtos.RuleRequest;
import com.smarthome.automation.AutomationDtos.RuleResponse;
import com.smarthome.automation.AutomationExceptions.InvalidRuleException;
import com.smarthome.automation.AutomationService;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceExceptions.DeviceNotFoundException;
import com.smarthome.device.DeviceService;
import com.smarthome.ml.MlProperties;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The chat flow:
 * <ol>
 * <li>validate, rate-limit, load the conversation and what it was about (its {@link Focus});</li>
 * <li>check active gas incidents (deterministic safety banner, independent of any model);</li>
 * <li>plan and run the database queries the question obviously needs ({@link QueryPlanner}), so real numbers are
 * always available;</li>
 * <li>hybrid retrieval over the owner's indexed facts, help text and documents ({@link HybridRetriever});</li>
 * <li>ask the model with all of it as numbered, fenced DATA; the model may call more tools ({@link ToolRegistry}), each
 * validated and owner-scoped, a bounded number of times;</li>
 * <li>check the answer's numbers and citations against the sources ({@link GroundingCheck}) and store it with its charts,
 * tables, follow-up questions and proposed (never executed) device actions.</li>
 * </ol>
 * Without a model, or when it fails, the answer is assembled from the same query results, so statistics are still
 * correct. If the database is down the user gets a 503 and nothing changes.
 *
 * Nothing here can reach a device: the only write paths are the chat tables, the vector store, reports (on request),
 * and, after an explicit approval by the owner, {@link AutomationService#create}.
 */
@Service
public class AssistantService {

	private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
	private static final String NOTICE = "The assistant explains your own data, computes statistics from the database and suggests rules. It cannot "
			+ "control devices: device changes and suggested rules only happen after you confirm or approve them. It can be wrong: check the sources. "
			+ "It is not a safety system; the device's own gas warning is independent of it.";

	static final int MAX_TOOL_ROUNDS = 3;
	static final int MAX_TOOL_CALLS = 6;
	static final int MAX_VISUALS = 6;
	/** After this long in the tool loop no further tool round starts (nginx in front of the API times out at 120 s). */
	static final long TOOL_BUDGET_NANOS = 45_000_000_000L;

	/** Progress of one answer, for streaming. Stages: "planning", "querying", "searching", "thinking", "tool", "checking". */
	@FunctionalInterface
	public interface Progress {
		Progress NONE = (stage, detail) -> { };

		void update(String stage, String detail);
	}

	private final AssistantProperties props;
	private final LlmClient llm;
	private final EmbeddingClient embedder;
	private final RagIndexer indexer;
	private final RagChunkStore store;
	private final HybridRetriever retriever;
	private final ToolRegistry tools;
	private final AnalyticsRepository analytics;
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
	private final ZoneId defaultZone;

	public AssistantService(AssistantProperties props, LlmClient llm, EmbeddingClient embedder, RagIndexer indexer, RagChunkStore store,
			HybridRetriever retriever, ToolRegistry tools, AnalyticsRepository analytics, PromptBuilder prompts, SuggestionParser parser,
			ChatConversationRepository conversations, ChatMessageRepository messages, AssistantSuggestionRepository suggestions,
			AutomationService automation, DeviceService deviceService, ObjectMapper mapper, ChatRateLimiter limiter, Clock clock, MlProperties ml) {
		this.limiter = limiter;
		this.props = props;
		this.llm = llm;
		this.embedder = embedder;
		this.indexer = indexer;
		this.store = store;
		this.retriever = retriever;
		this.tools = tools;
		this.analytics = analytics;
		this.prompts = prompts;
		this.parser = parser;
		this.conversations = conversations;
		this.messages = messages;
		this.suggestions = suggestions;
		this.automation = automation;
		this.deviceService = deviceService;
		this.mapper = mapper;
		this.clock = clock;
		this.defaultZone = ml.zone();
	}

	// ---- chat ----------------------------------------------------------------------------------

	public ChatResponse chat(UUID ownerId, UUID conversationId, String rawMessage) {
		return chat(ownerId, conversationId, rawMessage, null, Progress.NONE);
	}

	public ChatResponse chat(UUID ownerId, UUID conversationId, String rawMessage, String timeZone, Progress progress) {
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
		ZoneId zone = zone(timeZone);
		limiter.check(ownerId);
		Instant now = clock.instant();

		ChatConversation conv;
		List<Message> history;
		ChatMessage userMsg;
		Focus focus;
		try {
			conv = conversationId == null ? newConversation(ownerId, question, now) : ownedConversation(ownerId, conversationId);
			history = history(conv.getId());
			focus = readFocus(conv.getContextJson());
			userMsg = messages.save(new ChatMessage(UUID.randomUUID(), conv.getId(), "user", question, null, null, null, now));
		} catch (DataAccessException e) {
			throw unavailable(e);
		}

		Answer a = answer(ownerId, question, history, focus, zone, progress);

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
					sourcesJson(a.sources), warnings.isEmpty() ? null : warningsJson(warnings), extrasJson(a.extras), clock.instant()));
			conv.touch(clock.instant());
			conv.setContextJson(focusJson(a.focus));
			conversations.save(conv);
			return new ChatResponse(conv.getId(), conv.getTitle(), toDto(userMsg, List.of()), toDto(assistantMsg, created));
		} catch (DataAccessException e) {
			throw unavailable(e);
		}
	}

	/** What the pipeline produced, before anything is stored. */
	private record Answer(AnswerStatus status, String text, List<SourceDto> sources, List<String> warnings, SuggestionParser.Proposal proposal,
			ExtrasDto extras, Focus focus) {
	}

	/** The tool results gathered for one answer, numbered as sources. */
	private static final class Gathered {
		final List<ToolResult> results = new ArrayList<>();
		final List<Hit> asHits = new ArrayList<>();
		final Map<UUID, String> names;

		Gathered(Map<UUID, String> names) {
			this.names = names;
		}
	}

	private Answer answer(UUID ownerId, String question, List<Message> history, Focus focus, ZoneId zone, Progress progress) {
		Instant now = clock.instant();
		Map<UUID, String> names;
		String safety;
		try {
			names = names(ownerId);
			safety = safetyNotice(analytics.activeGasAlerts(ownerId), zone);
		} catch (DataAccessException e) {
			throw unavailable(e);
		}
		ToolContext ctx = new ToolContext(ownerId, zone, now, names);

		// 1. the queries the question obviously needs
		progress.update("planning", "Understanding the question");
		Plan plan = QueryPlanner.plan(question, names.values(), focus, mapper);
		Gathered g = new Gathered(names);
		for (PlannedCall c : plan.calls()) {
			progress.update("querying", label(c.tool()));
			record(g, tools.call(ctx, c.tool(), c.argumentsJson()), ownerId, now);
		}
		Focus newFocus = focusAfter(plan.focus(), g.results, names);

		// 2. hybrid retrieval over indexed facts, help text and documents
		progress.update("searching", "Searching your records and notes");
		List<Hit> hits;
		boolean retrievalFailed = false;
		try {
			indexer.ensureKnowledge();
			indexer.refreshLive(ownerId);
			int topK = props.rag().topK();
			hits = retriever.retrieve(ownerId, question, topK, props.rag().minScore(), asksAboutReadings(question) && g.results.isEmpty(), names);
		} catch (LlmUnavailableException e) {
			if (g.results.isEmpty()) {
				return new Answer(AnswerStatus.RETRIEVAL_UNAVAILABLE, "I could not search your data right now: " + e.getMessage()
						+ " Nothing was guessed. Please try again later.", List.of(), List.of(), null, extras(g, List.of(), safety, plan, newFocus), newFocus);
			}
			hits = List.of();
			retrievalFailed = true;
		} catch (DataAccessException e) {
			throw unavailable(e);
		}
		List<Hit> mine = new ArrayList<>();
		for (Hit h : hits) {
			if (h.ownerUserId() != null && !h.ownerUserId().equals(ownerId)) {
				log.error("Dropped a retrieved chunk that belongs to another user (this must never happen)");     // second line of defence
				continue;
			}
			mine.add(h);
		}
		List<String> baseWarnings = new ArrayList<>();
		if (retrievalFailed) {
			baseWarnings.add("Your notes and help text could not be searched right now; this answer uses the database queries only.");
		}
		boolean anyData = g.results.stream().anyMatch(ToolResult::ok);
		if (mine.isEmpty() && !anyData && !(llm.configured() && llm.supportsTools())) {
			return new Answer(AnswerStatus.NO_CONTEXT, "I could not find anything about that in your devices, readings, anomalies, automation rules "
					+ "or the help text, so I will not guess. Try naming a device, a sensor or a period (for example \"temperature yesterday\"), or add a "
					+ "document under Notes.", List.of(), List.of(), null, extras(g, List.of(), safety, plan, newFocus), newFocus);
		}

		List<Hit> context = new ArrayList<>(g.asHits);
		context.addAll(mine);
		Prompt prompt = prompts.build(question, history, context, props.rag().maxContextChars(), focusNote(newFocus));

		if (!llm.configured()) {
			String text = anyData ? dataAnswer(g, prompt.sources(), "No AI language model is set up (" + llm.notConfiguredReason().trim()
					+ "), so this answer was assembled directly from your database, without AI:")
					: "No AI language model is set up (" + llm.notConfiguredReason().trim()
							+ "). Here are the most relevant records I found; they are real stored data, not a generated answer.";
			return new Answer(AnswerStatus.NOT_CONFIGURED, text, toSources(prompt.sources(), List.of()), baseWarnings, null,
					extras(g, List.of(), safety, plan, newFocus), newFocus);
		}

		// 3. the model, with tools
		progress.update("thinking", "Writing the answer");
		List<Source> sources = new ArrayList<>(prompt.sources());
		StringBuilder sourceText = new StringBuilder(prompt.contextText());
		String reply;
		try {
			reply = converse(prompt, ctx, g, sources, sourceText, ownerId, now, progress);
		} catch (LlmUnavailableException e) {
			String text = (anyData ? dataAnswer(g, sources, "The AI model could not answer (" + e.getMessage().strip() + "). Here is what your database shows:")
					: "The AI model could not answer: " + e.getMessage() + " Here are the most relevant records I found.");
			return new Answer(AnswerStatus.LLM_UNAVAILABLE, text, toSources(sources, List.of()), baseWarnings, null, extras(g, List.of(), safety, plan, newFocus),
					focusAfter(newFocus, g.results, names));
		} catch (RuntimeException e) {
			log.warn("Language model client failed unexpectedly: {}", e.getClass().getSimpleName());
			String text = anyData ? dataAnswer(g, sources, "The AI model could not answer because of an unexpected error. Here is what your database shows:")
					: "The AI model could not answer because of an unexpected error. Here are the most relevant records I found.";
			return new Answer(AnswerStatus.LLM_UNAVAILABLE, text, toSources(sources, List.of()), baseWarnings, null, extras(g, List.of(), safety, plan, newFocus),
					newFocus);
		}

		progress.update("checking", "Checking the answer against the data");
		SuggestionParser.Parsed parsed = parser.parse(reply);
		String visible = parsed.visibleText();
		if (visible.isBlank()) {
			visible = parsed.proposal() != null ? "I prepared a rule suggestion below. It does nothing until you approve it." : "The AI model gave no answer.";
		}
		GroundingCheck.Result check = GroundingCheck.check(visible, sources.size(), sourceText.toString(), question);
		List<String> warnings = new ArrayList<>(baseWarnings);
		warnings.addAll(check.warnings());
		if (parsed.problem() != null) {
			warnings.add("Rule suggestion: " + parsed.problem() + ".");
		}
		for (ToolResult r : g.results) {
			if (!r.ok() && r.error() != null && r.error().contains("database unavailable")) {
				warnings.add("Some of your data could not be read; the answer may be incomplete.");
				break;
			}
		}
		Focus finalFocus = focusAfter(newFocus, g.results, names);
		return new Answer(AnswerStatus.ANSWERED, check.text(), toSources(sources, check.cited()), warnings, parsed.proposal(),
				extras(g, check.cited(), safety, plan, finalFocus), finalFocus);
	}

	/**
	 * The model conversation: it may ask for tools up to {@link #MAX_TOOL_ROUNDS} times ({@link #MAX_TOOL_CALLS} calls in
	 * all); each call is validated and executed by the registry and its result is added as a new numbered source. If the
	 * provider rejects tool use, the question is answered once more without tools from the data already gathered.
	 */
	private String converse(Prompt prompt, ToolContext ctx, Gathered g, List<Source> sources, StringBuilder sourceText, UUID ownerId, Instant now,
			Progress progress) throws LlmUnavailableException {
		if (!llm.supportsTools()) {
			return llm.complete(prompt.system(), prompt.messages());
		}
		List<Message> msgs = new ArrayList<>(prompt.messages());
		int calls = 0;
		long started = System.nanoTime();
		for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
			if (round > 0 && System.nanoTime() - started > TOOL_BUDGET_NANOS) {
				break;                                          // slow provider: answer now, so the request stays inside the proxy timeout
			}
			Turn t;
			try {
				t = llm.completeWithTools(prompt.system(), msgs, calls >= MAX_TOOL_CALLS ? List.of() : tools.specs());
			} catch (LlmUnavailableException e) {
				if (e.reason() == LlmUnavailableException.Reason.BAD_RESPONSE) {
					// e.g. Groq's 400 "tool_use_failed": answer once more without tools from the data gathered before the model ran
					log.info("The model's tool request failed ({}); answering without tools", e.reason());
					return llm.complete(prompt.system(), prompt.messages());
				}
				throw e;
			}
			if (!t.wantsTools()) {
				return t.text();
			}
			List<ToolCall> accepted = new ArrayList<>();
			List<Message> results = new ArrayList<>();
			for (ToolCall c : t.toolCalls()) {
				if (calls >= MAX_TOOL_CALLS) {
					break;
				}
				calls++;
				String id = c.id() == null || c.id().isBlank() ? "call_" + calls : Text.oneLine(c.id(), 64);
				progress.update("tool", label(c.name()));
				ToolResult r = tools.call(ctx, c.name(), c.arguments());
				Hit h = record(g, r, ownerId, now);
				String ref = null;
				if (h != null) {
					ref = "S" + (sources.size() + 1);           // after every source the model has already seen
					sources.add(new Source(ref, h));
					sourceText.append(h.content()).append('\n');
				}
				accepted.add(new ToolCall(id, Text.oneLine(c.name(), 60),
						c.arguments() == null ? "{}" : Text.oneLine(c.arguments(), com.smarthome.assistant.tools.ToolArgs.MAX_JSON), c.signature()));
				results.add(prompts.toolTurn(id, c.name(), ref == null ? "error" : ref, r.textForModel()));
			}
			msgs.add(Message.assistantCalls(t.text(), accepted));
			msgs.addAll(results);
		}
		// Out of rounds: ask for the answer from what is there.
		Turn last = llm.completeWithTools(prompt.system(), msgs, List.of());
		if (last.text() == null || last.text().isBlank()) {
			throw new LlmUnavailableException(LlmUnavailableException.Reason.BAD_RESPONSE, "The language model returned an empty answer.");
		}
		return last.text();
	}

	/** Records a tool result; a successful one becomes a LIVE_DATA pseudo-chunk (the caller numbers it). @return it, or null */
	private Hit record(Gathered g, ToolResult r, UUID ownerId, Instant now) {
		g.results.add(r);
		if (!r.ok()) {
			return null;
		}
		UUID device = null;                                  // a query about exactly one device is linked to it
		String focused = r.output().focus() == null ? null : r.output().focus().device();
		if (focused != null) {
			for (Map.Entry<UUID, String> e : g.names.entrySet()) {
				if (e.getValue().equals(focused)) {
					device = e.getKey();
				}
			}
		}
		Hit h = new Hit(UUID.randomUUID(), ownerId, device, LIVE_DATA, "tool:" + r.tool(), 0, r.output().title(), r.output().text(), now, 1.0);
		g.asHits.add(h);
		return h;
	}

	public static final String LIVE_DATA = "LIVE_DATA";

	/** The answer without a model: each query result with its title and, when it made it into the sources, its reference. */
	private static String dataAnswer(Gathered g, List<Source> sources, String intro) {
		Map<UUID, String> refs = new HashMap<>();
		sources.forEach(s -> refs.put(s.hit().id(), s.ref()));
		StringBuilder sb = new StringBuilder(intro).append("\n\n");
		for (Hit h : g.asHits) {
			String ref = refs.get(h.id());
			sb.append("**").append(h.title()).append("**").append(ref == null ? "" : " [" + ref + "]").append("\n\n").append(h.content()).append("\n\n");
		}
		return sb.toString().strip();
	}

	private static String label(String tool) {
		return switch (tool == null ? "" : tool) {
			case "get_current_readings" -> "Reading the latest sensor values";
			case "get_sensor_statistics" -> "Computing statistics in the database";
			case "get_sensor_trend" -> "Building the trend";
			case "compare_periods" -> "Comparing periods";
			case "get_motion_activity" -> "Analysing motion and occupancy";
			case "get_device_status" -> "Checking device connectivity";
			case "get_anomalies" -> "Looking up anomalies";
			case "get_automation_history" -> "Reading automation history";
			case "get_security_events" -> "Reading security events";
			case "get_gas_incidents" -> "Reading gas incident records";
			case "get_command_history" -> "Reading LED command history";
			case "get_system_health" -> "Checking system health";
			case "search_knowledge" -> "Searching help and notes";
			case "create_report" -> "Generating the report";
			case "propose_device_command" -> "Preparing a device command for your confirmation";
			default -> "Running a query";
		};
	}

	/** Deterministic: an active gas incident always produces this banner, whatever the model says or whether there is one. */
	static String safetyNotice(List<GasAlertRow> active, ZoneId zone) {
		if (active.isEmpty()) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		for (GasAlertRow a : active) {
			parts.add(Text.oneLine(a.deviceName(), 60) + " since " + Fmt.time(a.startedAt(), zone) + " (peak " + a.peak() + " raw, threshold " + a.threshold() + ")");
		}
		return "Gas alert ACTIVE: " + String.join("; ", parts) + ". Follow the device's warning and local emergency guidance: ventilate, avoid flames and "
				+ "switches, and leave the area if you smell gas. This notice comes from the alert system, not from the AI.";
	}

	private ExtrasDto extras(Gathered g, List<Integer> cited, String safety, Plan plan, Focus focus) {
		List<Visual> visuals = new ArrayList<>();
		List<ActionDto> actions = new ArrayList<>();
		List<UUID> reports = new ArrayList<>();
		List<ToolUseDto> used = new ArrayList<>();
		for (ToolResult r : g.results) {
			used.add(new ToolUseDto(r.tool(), r.argumentsJson(), r.ok(), r.ok() ? null : Text.oneLine(r.error(), 200)));
			if (!r.ok()) {
				continue;
			}
			for (Visual v : r.output().visuals()) {
				if (visuals.size() < MAX_VISUALS) {
					visuals.add(v);
				}
			}
			PendingAction p = r.output().action();
			if (p != null) {
				actions.add(new ActionDto(p.deviceId(), p.deviceName(), p.action(), p.value(), p.description()));
			}
			if (r.output().reportId() != null) {
				reports.add(r.output().reportId());
			}
		}
		return new ExtrasDto(visuals, actions, reports, followUps(g.results, focus), safety, used);
	}

	/** Suggested next questions, from what was just looked at (deterministic, so they always make sense for the data). */
	static List<String> followUps(List<ToolResult> results, Focus focus) {
		Set<String> out = new LinkedHashSet<>();
		String metric = focus == null || focus.metric() == null || "motion".equals(focus.metric()) ? "temperature" : focus.metric();
		for (ToolResult r : results) {
			if (!r.ok()) {
				continue;
			}
			switch (r.tool()) {
				case "get_current_readings" -> {
					out.add("How did the " + metric + " change over the last 24 hours?");
					out.add("Compare today with yesterday");
				}
				case "get_sensor_statistics" -> {
					out.add("Show the " + metric + " trend for the same period");
					out.add("Compare it with the previous period");
				}
				case "get_sensor_trend" -> {
					out.add("What were the highest and lowest " + metric + " values?");
					out.add("Compare it with last week");
				}
				case "compare_periods" -> out.add("Show the hourly " + metric + " trend for today");
				case "get_motion_activity" -> {
					out.add("Were there any security events this week?");
					out.add("When is the house usually busiest?");
				}
				case "get_anomalies" -> out.add("Generate a weekly report");
				case "get_device_status" -> out.add("Is the whole system healthy?");
				case "get_security_events" -> out.add("Show motion activity for today");
				case "get_gas_incidents" -> {
					out.add("How has the gas level changed over the last 24 hours?");
					out.add("What is the current gas reading?");
				}
				case "get_command_history" -> out.add("What is the current state of my devices?");
				case "get_system_health" -> out.add("Which devices went offline this week?");
				case "create_report" -> out.add("What should I improve based on this report?");
				default -> { }
			}
		}
		if (out.isEmpty()) {
			out.add("What are the current readings?");
			out.add("Summarise the last 7 days");
			out.add("Were there any anomalies this week?");
		}
		return out.stream().limit(3).toList();
	}

	/** The focus after this turn: the planner's, refined by the arguments of the tools that actually ran. */
	private Focus focusAfter(Focus planned, List<ToolResult> results, Map<UUID, String> names) {
		Focus f = planned == null ? Focus.EMPTY : planned;
		for (ToolResult r : results) {
			if (r.ok() && r.output().focus() != null) {
				f = f.merge(r.output().focus());
			}
		}
		if (f.device() != null && !names.containsValue(f.device())) {
			f = new Focus(f.metric(), f.period(), f.from(), f.to(), null);     // a device that is no longer the caller's
		}
		return f;
	}

	static String focusNote(Focus f) {
		if (f == null || (f.metric() == null && f.period() == null && f.device() == null)) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		if (f.metric() != null) {
			parts.add("sensor " + f.metric());
		}
		if (f.period() != null) {
			parts.add("period " + f.period() + ("custom".equals(f.period()) && f.from() != null ? " " + f.from() + " to " + f.to() : ""));
		}
		if (f.device() != null) {
			parts.add("device \"" + f.device() + "\"");
		}
		return String.join(", ", parts);
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
				embedder.local() ? "Built-in word-based embedder: matches wording, not meaning (keyword search is combined with it). Set EMBEDDING_PROVIDER=openai for semantic search." : "");
		IndexInfo index = guard(() -> new IndexInfo(store.countOwned(ownerId), store.countShared(), store.sources(ownerId, RagIndexer.DOCUMENT).size()));
		AssistantProperties.Rag r = props.rag();
		return new StatusResponse(l, e, index, new LimitsInfo(props.chat().maxMessageChars(), r.maxDocumentChars(), r.maxDocumentsPerUser(), r.topK()), NOTICE);
	}

	// ---- helpers -------------------------------------------------------------------------------

	ZoneId zone(String name) {
		if (name == null || name.isBlank()) {
			return defaultZone;
		}
		try {
			return ZoneId.of(Text.oneLine(name, 50));
		} catch (DateTimeException e) {
			return defaultZone;                                      // a bad browser zone must not break the chat
		}
	}

	private Focus readFocus(String json) {
		if (json == null || json.isBlank()) {
			return Focus.EMPTY;
		}
		try {
			return mapper.readValue(json, Focus.class);
		} catch (RuntimeException e) {
			return Focus.EMPTY;
		}
	}

	private String focusJson(Focus f) {
		if (f == null) {
			return null;
		}
		Focus clean = new Focus(cap(f.metric(), 20), cap(f.period(), 20), cap(f.from(), 10), cap(f.to(), 10), cap(f.device(), 60));
		String s = json(clean);
		return s.length() <= 2000 ? s : null;
	}

	private static String cap(String s, int max) {
		return s == null ? null : Text.oneLine(s, max);
	}

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
			out.add(new SourceDto(s.ref(), h.sourceType(), h.title(), Text.oneLine(body, LIVE_DATA.equals(h.sourceType()) ? 600 : 280),
					Math.round(h.score() * 1000) / 1000.0, h.asOf(), h.deviceUuid(), cited.contains(n)));
		}
		return out;
	}

	private MessageDto toDto(ChatMessage m, List<SuggestionDto> sugg) {
		List<SourceDto> sources = m.getSourcesJson() == null ? List.of() : read(m.getSourcesJson(), new TypeReference<List<SourceDto>>() { });
		List<String> warnings = m.getWarningsJson() == null ? List.of() : read(m.getWarningsJson(), new TypeReference<List<String>>() { });
		AnswerStatus status = m.getAnswerStatus() == null ? null : AnswerStatus.valueOf(m.getAnswerStatus());
		ExtrasDto extras = m.getExtrasJson() == null ? ExtrasDto.NONE : read(m.getExtrasJson(), new TypeReference<ExtrasDto>() { });
		return new MessageDto(m.getId(), m.getRole(), m.getContent(), status, sources, warnings, sugg, m.getCreatedAt(), extras);
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
		Map<UUID, String> out = new LinkedHashMap<>();
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

	/** Extras as JSON that fits the column: visuals are dropped from the end first (they are the large part). */
	private String extrasJson(ExtrasDto e) {
		ExtrasDto x = e;
		String s = json(x);
		while (s.length() > 59000 && !x.visuals().isEmpty()) {
			x = new ExtrasDto(x.visuals().subList(0, x.visuals().size() - 1), x.actions(), x.reports(), x.followUps(), x.safety(), x.tools());
			s = json(x);
		}
		return s.length() > 59000 ? json(new ExtrasDto(List.of(), x.actions(), x.reports(), x.followUps(), x.safety(), List.of())) : s;
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
