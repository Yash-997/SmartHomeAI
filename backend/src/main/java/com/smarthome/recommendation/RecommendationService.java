package com.smarthome.recommendation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.assistant.AssistantService;
import com.smarthome.assistant.Text;
import com.smarthome.automation.AutomationDtos.ActionRequest;
import com.smarthome.automation.AutomationDtos.ConditionRequest;
import com.smarthome.automation.AutomationDtos.ExecutionResponse;
import com.smarthome.automation.AutomationDtos.RuleRequest;
import com.smarthome.automation.AutomationDtos.RuleResponse;
import com.smarthome.automation.AutomationDtos.TriggerRequest;
import com.smarthome.automation.AutomationExceptions.InvalidRuleException;
import com.smarthome.automation.AutomationService;
import com.smarthome.automation.ExecutionOutcome;
import com.smarthome.command.CommandActions;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceExceptions.DeviceNotFoundException;
import com.smarthome.device.DeviceService;
import com.smarthome.ml.AnomalyKind;
import com.smarthome.ml.AnomalyService;
import com.smarthome.ml.MlDtos.AnomalyResponse;
import com.smarthome.ml.MlDtos.ForecastResponse;
import com.smarthome.ml.MlDtos.OccupancyStatusResponse;
import com.smarthome.ml.MlDtos.PredictionResponse;
import com.smarthome.ml.MlExceptions.TooFrequentException;
import com.smarthome.ml.MlSampleRepository;
import com.smarthome.ml.OccupancyService;
import com.smarthome.ml.SensorSample;
import com.smarthome.recommendation.RecommendationDtos.RecommendationList;
import com.smarthome.recommendation.RecommendationDtos.RecommendationResponse;
import com.smarthome.recommendation.RecommendationExceptions.RecommendationNotFoundException;
import com.smarthome.recommendation.RecommendationExceptions.RecommendationNotPendingException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.DoubleNode;

/**
 * Suggests rules from the user's own data, and applies one only when the user approves it.
 *
 * <p>Every suggestion rests on numbers that are stored and shown with it (its evidence). Nothing is guessed: if a model is
 * not ready or there is too little data, the reason is returned as a note instead of a suggestion. The only output a
 * suggestion can set is the PIR indicator LED. Gas is never a trigger or a target here, and any rule still goes through
 * {@link AutomationService#create} on approval, which re-checks ownership and the gas-LED rule.
 */
@Service
public class RecommendationService {

	static final double QUIET_PROBABILITY = 0.10;
	static final int MIN_QUIET_HOURS = 4;
	static final int MIN_TEMPERATURE_SAMPLES = 200;
	static final int MIN_OUTLIERS = 3;
	static final int MIN_RULE_RUNS = 5;
	static final double BAD_RUN_SHARE = 0.7;
	private static final Duration DISMISS_QUIET = Duration.ofDays(14);
	private static final Duration APPLIED_QUIET = Duration.ofDays(30);
	private final Duration autoRefresh;
	private final Duration minManualRefresh;
	private static final List<String> ALL_DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
	private static final Set<ExecutionOutcome> BAD = Set.of(ExecutionOutcome.FAILED, ExecutionOutcome.SKIPPED_OFFLINE, ExecutionOutcome.SKIPPED_NOT_OWNED);

	private final RecommendationRepository repo;
	private final DeviceService devices;
	private final OccupancyService occupancy;
	private final AnomalyService anomalies;
	private final AutomationService automation;
	private final MlSampleRepository samples;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final Map<UUID, Instant> lastGenerated = new ConcurrentHashMap<>();

	public RecommendationService(RecommendationRepository repo, DeviceService devices, OccupancyService occupancy, AnomalyService anomalies,
			AutomationService automation, MlSampleRepository samples, ObjectMapper mapper, Clock clock,
			@org.springframework.beans.factory.annotation.Value("${app.recommendations.auto-refresh-seconds:300}") long autoRefreshSeconds,
			@org.springframework.beans.factory.annotation.Value("${app.recommendations.min-manual-refresh-seconds:15}") long minManualRefreshSeconds) {
		this.autoRefresh = Duration.ofSeconds(autoRefreshSeconds);
		this.minManualRefresh = Duration.ofSeconds(minManualRefreshSeconds);
		this.repo = repo;
		this.devices = devices;
		this.occupancy = occupancy;
		this.anomalies = anomalies;
		this.automation = automation;
		this.samples = samples;
		this.mapper = mapper;
		this.clock = clock;
	}

	// ---- public API ----------------------------------------------------------------------------

	/** Pending suggestions. Regenerated if the last run is older than five minutes. */
	public RecommendationList list(UUID owner) {
		Instant last = lastGenerated.get(owner);
		if (last == null || !last.isAfter(clock.instant().minus(autoRefresh))) {
			return generate(owner);
		}
		return current(owner, List.of());
	}

	/** Regenerates now (limited to once per 15 seconds per user). */
	public RecommendationList refresh(UUID owner) {
		Instant last = lastGenerated.get(owner);
		if (last != null && minManualRefresh.toSeconds() > 0 && last.isAfter(clock.instant().minus(minManualRefresh))) {
			throw new TooFrequentException("Recommendations were just refreshed. Wait a few seconds.");
		}
		return generate(owner);
	}

	/** The owner's explicit approval; the change is made through the normal automation service. */
	@Transactional
	public RecommendationResponse approve(UUID owner, UUID id) {
		Recommendation r = repo.findByIdAndOwnerUserId(id, owner).orElseThrow(RecommendationNotFoundException::new);
		Instant now = clock.instant();
		if (repo.claim(id, owner, Recommendation.APPLIED, now) == 0) {
			throw new RecommendationNotPendingException("This recommendation was already " + r.getStatus().toLowerCase() + ".");
		}
		UUID ruleId;
		try {
			if (Recommendation.CREATE_RULE.equals(r.getActionType())) {
				ruleId = automation.create(owner, mapper.readValue(r.getPayload(), RuleRequest.class)).id();
			} else {
				UUID target = UUID.fromString(mapper.readTree(r.getPayload()).path("ruleId").asString(""));
				automation.setEnabled(owner, target, false);
				ruleId = target;
			}
		} catch (IllegalArgumentException e) {
			throw new RecommendationNotPendingException("This recommendation can no longer be read.");
		}
		r.decide(Recommendation.APPLIED, now, ruleId);
		return toResponse(r, names(owner));
	}

	@Transactional
	public RecommendationResponse dismiss(UUID owner, UUID id) {
		Recommendation r = repo.findByIdAndOwnerUserId(id, owner).orElseThrow(RecommendationNotFoundException::new);
		Instant now = clock.instant();
		if (repo.claim(id, owner, Recommendation.DISMISSED, now) == 0) {
			throw new RecommendationNotPendingException("This recommendation was already " + r.getStatus().toLowerCase() + ".");
		}
		r.decide(Recommendation.DISMISSED, now, null);
		return toResponse(r, names(owner));
	}

	// ---- generation ----------------------------------------------------------------------------

	private record Candidate(UUID device, String type, String fingerprint, String title, String rationale, Map<String, Object> evidence, Object payload) {
	}

	private synchronized RecommendationList generate(UUID owner) {
		Instant now = clock.instant();
		List<String> notes = new ArrayList<>();
		List<Candidate> found = new ArrayList<>();
		List<DeviceResponse> mine = devices.list(owner);
		List<RuleResponse> rules = automation.list(owner);
		if (mine.isEmpty()) {
			notes.add("Pair a device first: recommendations are built from your own readings.");
		}
		for (DeviceResponse d : mine) {
			String name = Text.oneLine(d.name(), 60);
			quietHours(owner, d, name, rules, found, notes);
			temperatureAlert(owner, d, name, rules, found, notes, now);
		}
		ruleHealth(owner, rules, found, notes, now);

		// keep what is still valid, drop what is not, skip what the user already decided about
		Set<String> valid = new HashSet<>();
		for (Candidate c : found) {
			if (!acceptable(owner, c, now, notes)) {
				continue;
			}
			valid.add(c.fingerprint());
			boolean pending = repo.findByOwnerUserIdAndFingerprintOrderByCreatedAtDesc(owner, c.fingerprint()).stream()
					.anyMatch(r -> Recommendation.PENDING.equals(r.getStatus()));
			if (!pending) {
				repo.save(new Recommendation(owner, c.device(), c.type(), c.fingerprint(), c.title(), c.rationale(), json(c.evidence()), json(c.payload()), now));
			}
		}
		for (Recommendation r : repo.findByOwnerUserIdAndStatus(owner, Recommendation.PENDING)) {
			if (!valid.contains(r.getFingerprint())) {
				r.markStale(now);
				repo.save(r);
			}
		}
		if (valid.isEmpty() && !mine.isEmpty() && notes.isEmpty()) {
			notes.add("Nothing to suggest right now: your data shows no pattern strong enough.");
		}
		lastGenerated.put(owner, now);
		return current(owner, notes);
	}

	/** Not already decided recently, not already in place, and valid for this user right now. */
	private boolean acceptable(UUID owner, Candidate c, Instant now, List<String> notes) {
		for (Recommendation old : repo.findByOwnerUserIdAndFingerprintOrderByCreatedAtDesc(owner, c.fingerprint())) {
			if (Recommendation.DISMISSED.equals(old.getStatus()) && old.getDecidedAt() != null && old.getDecidedAt().isAfter(now.minus(DISMISS_QUIET))) {
				return false;
			}
			if (Recommendation.APPLIED.equals(old.getStatus()) && old.getDecidedAt() != null && old.getDecidedAt().isAfter(now.minus(APPLIED_QUIET))) {
				return false;
			}
		}
		if (c.payload() instanceof RuleRequest req) {
			try {
				automation.check(owner, req);                     // the same checks creating it would make
			} catch (InvalidRuleException | DeviceNotFoundException e) {
				notes.add("\"" + c.title() + "\" was not offered: it would not be accepted right now.");
				return false;
			}
		}
		return true;
	}

	private RecommendationList current(UUID owner, List<String> notes) {
		Map<UUID, String> names = names(owner);
		List<RecommendationResponse> list = repo.findByOwnerUserIdAndStatusOrderByCreatedAtDesc(owner, Recommendation.PENDING, PageRequest.of(0, 50)).stream()
				.map(r -> toResponse(r, names)).toList();
		return new RecommendationList(list, notes, lastGenerated.getOrDefault(owner, clock.instant()));
	}

	// ---- generator 1: quiet hours from the occupancy model -------------------------------------

	private void quietHours(UUID owner, DeviceResponse d, String name, List<RuleResponse> rules, List<Candidate> out, List<String> notes) {
		OccupancyStatusResponse st;
		try {
			st = occupancy.status(owner, d.id());
		} catch (RuntimeException e) {
			return;
		}
		if (!"READY".equals(st.state())) {
			notes.add("\"" + name + "\": no quiet-hours suggestion because the occupancy model is " + st.state().toLowerCase().replace('_', ' ') + ".");
			return;
		}
		ForecastResponse f = occupancy.forecast(owner, d.id(), null, 24, 60);
		if (f.points().size() < 24) {
			return;
		}
		boolean[] quiet = new boolean[24];
		double[] prob = new double[24];
		for (PredictionResponse p : f.points()) {
			prob[p.hour()] = p.probability();
			quiet[p.hour()] = p.reliable() && p.probability() < QUIET_PROBABILITY;
		}
		int bestStart = -1;
		int bestLen = 0;
		for (int s = 0; s < 24; s++) {
			if (!quiet[s] || quiet[(s + 23) % 24]) {
				continue;                                              // not the start of a run
			}
			int len = 0;
			while (len < 24 && quiet[(s + len) % 24]) {
				len++;
			}
			if (len > bestLen) {
				bestLen = len;
				bestStart = s;
			}
		}
		if (bestStart < 0 || bestLen < MIN_QUIET_HOURS || bestLen >= 24) {
			notes.add("\"" + name + "\": no quiet period of at least " + MIN_QUIET_HOURS + " hours was found in the occupancy forecast.");
			return;
		}
		int endHour = (bestStart + bestLen) % 24;
		double max = 0;
		for (int i = 0; i < bestLen; i++) {
			max = Math.max(max, prob[(bestStart + i) % 24]);
		}
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("quietFrom", hhmm(bestStart));
		ev.put("quietUntil", hhmm(endHour));
		ev.put("quietHours", bestLen);
		ev.put("highestMotionChanceInWindow", Math.round(max * 1000) / 1000.0);
		ev.put("threshold", QUIET_PROBABILITY);
		ev.put("timeZone", f.timeZone());
		ev.put("modelAccuracy", st.metrics() == null ? null : Math.round(st.metrics().accuracy() * 1000) / 1000.0);
		ev.put("modelBaselineAccuracy", st.metrics() == null ? null : Math.round(st.metrics().baselineAccuracy() * 1000) / 1000.0);

		String why = "The occupancy model for \"" + name + "\" (state READY) expects no motion between " + hhmm(bestStart) + " and " + hhmm(endHour)
				+ " (" + f.timeZone() + "): the highest chance of motion in those " + bestLen + " hours is " + Math.round(max * 100) + "%. "
				+ "\"Occupied\" means the PIR sensor fired, so this is a guess about activity, not about people.";
		if (!hasScheduleRule(rules, d.id(), hhmm(bestStart), "off")) {
			out.add(new Candidate(d.id(), Recommendation.CREATE_RULE, "quiet-start:" + d.id() + ":" + hhmm(bestStart), "Turn the PIR LED off during quiet hours (from " + hhmm(bestStart) + ")",
					why, ev, scheduleRule("Quiet hours start", d.id(), hhmm(bestStart), f.timeZone(), "off")));
		}
		if (!hasScheduleRule(rules, d.id(), hhmm(endHour), "auto")) {
			out.add(new Candidate(d.id(), Recommendation.CREATE_RULE, "quiet-end:" + d.id() + ":" + hhmm(endHour), "Return the PIR LED to auto after quiet hours (at " + hhmm(endHour) + ")",
					why, ev, scheduleRule("Quiet hours end", d.id(), hhmm(endHour), f.timeZone(), "auto")));
		}
	}

	// ---- generator 2: temperature indicator from readings and anomalies ------------------------

	private void temperatureAlert(UUID owner, DeviceResponse d, String name, List<RuleResponse> rules, List<Candidate> out, List<String> notes, Instant now) {
		Instant from = now.minus(7, ChronoUnit.DAYS);
		List<AnomalyResponse> outliers = anomalies.list(owner, d.id(), false, 100).stream()
				.filter(a -> "temperature".equals(a.scope()) && a.kind() == AnomalyKind.STATISTICAL_OUTLIER && a.detectedAt().isAfter(from)).toList();
		// One row stands for one ongoing problem; its occurrences count the unusual readings seen.
		int unusual = outliers.stream().mapToInt(a -> Math.max(1, a.occurrences())).sum();
		if (unusual < MIN_OUTLIERS) {
			return;                                                   // no pattern worth a rule; stay silent
		}
		double[] temps = samples.findSensorSamples(d.id(), from, now.plusSeconds(1), PageRequest.of(0, 20_000)).stream()
				.map(SensorSample::temperature).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).sorted().toArray();
		if (temps.length < MIN_TEMPERATURE_SAMPLES) {
			notes.add("\"" + name + "\": " + unusual + " unusual temperature readings were found but only " + temps.length
					+ " temperature values are stored for the last 7 days (at least " + MIN_TEMPERATURE_SAMPLES + " are needed), so no threshold was suggested.");
			return;
		}
		double p95 = temps[(int) Math.min(temps.length - 1, Math.floor(0.95 * temps.length))];
		double threshold = Math.round((p95 + 2.0) * 2) / 2.0;       // 2 degrees above the 95th percentile, to the nearest 0.5
		if (threshold >= 80) {
			return;                                                   // outside what the sensor can report
		}
		if (rules.stream().anyMatch(r -> r.deviceId().equals(d.id()) && r.conditions().stream().anyMatch(c -> "temperature".equals(c.sensor()) && c.operator().startsWith(">")))) {
			return;                                                   // the user already has a high-temperature rule here
		}
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("unusualTemperatureReadingsLast7Days", unusual);
		ev.put("temperatureSamples", temps.length);
		ev.put("p95", Math.round(p95 * 10) / 10.0);
		ev.put("suggestedThreshold", threshold);
		RuleRequest req = new RuleRequest("High temperature indicator", d.id(), null, true, new TriggerRequest("TELEMETRY", null, null, null), "AND",
				List.of(new ConditionRequest("temperature", ">", DoubleNode.valueOf(threshold))),
				new ActionRequest(CommandActions.SET_PIR_LED, "on"), 10, 300);
		out.add(new Candidate(d.id(), Recommendation.CREATE_RULE, "temp-high:" + d.id(), "Light the PIR LED when \"" + name + "\" is hotter than " + threshold + " Â°C",
				"\"" + name + "\" had " + unusual + " unusual temperature readings in the last 7 days. Its 95th percentile over " + temps.length + " readings is "
						+ Math.round(p95 * 10) / 10.0 + " Â°C; this rule lights the PIR indicator LED above " + threshold + " Â°C (2 Â°C higher), at most once every 5 minutes. "
						+ "The PIR LED is used because it can be switched back to auto; the gas LED is never touched.", ev, req));
	}

	// ---- generator 3: rules that mostly fail ---------------------------------------------------

	private void ruleHealth(UUID owner, List<RuleResponse> rules, List<Candidate> out, List<String> notes, Instant now) {
		if (rules.isEmpty()) {
			return;
		}
		Instant from = now.minus(7, ChronoUnit.DAYS);
		Map<UUID, int[]> counts = new HashMap<>();                    // [bad, total]
		for (ExecutionResponse e : automation.history(owner, 200)) {
			if (e.lastAt().isBefore(from)) {
				continue;
			}
			int weight = Math.max(1, e.repeatCount());
			int[] c = counts.computeIfAbsent(e.ruleId(), k -> new int[2]);
			c[1] += weight;
			if (BAD.contains(e.outcome())) {
				c[0] += weight;
			}
		}
		for (RuleResponse r : rules) {
			int[] c = counts.get(r.id());
			if (!r.enabled() || c == null || c[1] < MIN_RULE_RUNS || c[0] < BAD_RUN_SHARE * c[1]) {
				continue;
			}
			Map<String, Object> ev = new LinkedHashMap<>();
			ev.put("ruleName", Text.oneLine(r.name(), 60));
			ev.put("runsLast7Days", c[1]);
			ev.put("failedOrTargetOffline", c[0]);
			out.add(new Candidate(r.deviceId(), Recommendation.DISABLE_RULE, "rule-failing:" + r.id(), "Disable the rule \"" + Text.oneLine(r.name(), 60) + "\"",
					c[0] + " of its " + c[1] + " runs in the last 7 days failed or found the target device offline, so it is not doing anything useful. "
							+ "Disabling keeps the rule so you can enable it again.", ev, Map.of("ruleId", r.id().toString())));
		}
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static boolean hasScheduleRule(List<RuleResponse> rules, UUID device, String time, String value) {
		return rules.stream().anyMatch(r -> "SCHEDULE".equals(r.trigger().type()) && time.equals(r.trigger().time()) && r.deviceId().equals(device)
				&& CommandActions.SET_PIR_LED.equals(r.action().action()) && value.equals(r.action().value()));
	}

	private static RuleRequest scheduleRule(String name, UUID device, String time, String zone, String value) {
		return new RuleRequest(name, device, null, true, new TriggerRequest("SCHEDULE", time, ALL_DAYS, zone), "AND", List.of(),
				new ActionRequest(CommandActions.SET_PIR_LED, value), 10, 60);
	}

	private static String hhmm(int hour) {
		return String.format("%02d:00", hour);
	}

	private Map<UUID, String> names(UUID owner) {
		Map<UUID, String> out = new HashMap<>();
		devices.list(owner).forEach(d -> out.put(d.id(), Text.oneLine(d.name(), 60)));
		return out;
	}

	private RecommendationResponse toResponse(Recommendation r, Map<UUID, String> names) {
		JsonNode evidence = mapper.readTree(r.getEvidence());
		RuleRequest rule = null;
		UUID ruleId = null;
		String description;
		if (Recommendation.CREATE_RULE.equals(r.getActionType())) {
			rule = mapper.readValue(r.getPayload(), RuleRequest.class);
			description = AssistantService.describe(rule, names);
		} else {
			ruleId = UUID.fromString(mapper.readTree(r.getPayload()).path("ruleId").asString(""));
			description = "Switch this rule off (it is kept, and can be switched on again from the Automation page).";
		}
		return new RecommendationResponse(r.getId(), r.getDeviceUuid(), r.getActionType(), r.getTitle(), r.getRationale(), evidence, description, rule, ruleId,
				r.getStatus(), r.getCreatedAt(), r.getDecidedAt(), r.getCreatedRuleId());
	}

	private String json(Object o) {
		return mapper.writeValueAsString(o);
	}

	/** Used by tests that need the fixed day list. */
	static List<String> allDays() {
		return Arrays.asList(ALL_DAYS.toArray(new String[0]));
	}
}

