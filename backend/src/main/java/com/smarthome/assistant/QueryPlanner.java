package com.smarthome.assistant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.smarthome.assistant.tools.ToolTypes.Focus;

import tools.jackson.databind.ObjectMapper;

/**
 * A deterministic first pass over a question: which tools almost certainly answer it, with which arguments. It runs
 * before the language model is asked, so:
 * <ul>
 * <li>the model always has the real numbers in front of it, even if it does not call a tool itself;</li>
 * <li>with no model (or a failing one) the user still gets an answer built from the same tool results;</li>
 * <li>follow-ups ("and yesterday?", "compare it with last week", "what about humidity?") are resolved against what the
 * conversation was last about ({@link Focus}), instead of relying on the model to remember.</li>
 * </ul>
 * It never invents a value: it only chooses queries. Unrecognised questions plan nothing.
 */
public final class QueryPlanner {

	public record PlannedCall(String tool, String argumentsJson) {
	}

	public record Plan(List<PlannedCall> calls, Focus focus, boolean followUp) {
		public boolean isEmpty() {
			return calls.isEmpty();
		}
	}

	static final int MAX_CALLS = 3;

	private static final Map<String, Pattern> METRICS = new LinkedHashMap<>();
	static {
		METRICS.put("temperature", Pattern.compile("\\b(temperature|temp|temps|degrees?|celsius|hot|hotter|cold|colder|warm|warmer|cool|cooler|heat)\\b"));
		METRICS.put("humidity", Pattern.compile("\\b(humidity|humid|damp|moisture|moist|dry|drier)\\b"));
		METRICS.put("gas", Pattern.compile("\\b(gas|smoke|mq-?2|lpg|air quality)\\b"));
	}

	/** Phrase -> period preset, longest phrases first so "last 7 days" wins over "last". */
	private static final Map<String, String> PERIODS = new LinkedHashMap<>();
	static {
		PERIODS.put("last 30 days", "last_30_days");
		PERIODS.put("past 30 days", "last_30_days");
		PERIODS.put("past month", "last_30_days");
		PERIODS.put("last 7 days", "last_7_days");
		PERIODS.put("past 7 days", "last_7_days");
		PERIODS.put("past week", "last_7_days");
		PERIODS.put("last 24 hours", "last_24h");
		PERIODS.put("past 24 hours", "last_24h");
		PERIODS.put("past day", "last_24h");
		PERIODS.put("last hour", "last_hour");
		PERIODS.put("past hour", "last_hour");
		PERIODS.put("this week", "this_week");
		PERIODS.put("last week", "last_week");
		PERIODS.put("previous week", "last_week");
		PERIODS.put("this month", "this_month");
		PERIODS.put("last month", "last_month");
		PERIODS.put("previous month", "last_month");
		PERIODS.put("yesterday", "yesterday");
		PERIODS.put("today", "today");
		PERIODS.put("tonight", "today");
		PERIODS.put("this morning", "today");
		PERIODS.put("weekly", "last_7_days");
		PERIODS.put("monthly", "last_30_days");
		PERIODS.put("daily", "last_24h");
	}

	private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
	private static final Pattern COMPARE = Pattern.compile("\\b(compare|compared|comparison|versus|vs\\.?|difference|differ|than)\\b");
	private static final Pattern TREND = Pattern.compile("\\b(trend|trends|over time|chart|graph|plot|hourly|daily|by hour|by day|pattern|history|historical|changed?)\\b");
	private static final Pattern STATS = Pattern.compile("\\b(average|avg|mean|max|maximum|min|minimum|highest|lowest|peak|stat|stats|statistics|summary|summarise|summarize|range)\\b");
	private static final Pattern CURRENT = Pattern.compile("\\b(current|currently|now|right now|latest|at the moment|live|reading|readings)\\b");
	private static final Pattern MOTION = Pattern.compile("\\b(motion|movement|moving|occupan\\w*|occupied|someone|anyone|anybody|presence|activity|active)\\b");
	private static final Pattern DEVICES = Pattern.compile("\\b(online|offline|connected|disconnected|connection|connectivity|devices?)\\b");
	private static final Pattern ANOMALY = Pattern.compile("\\b(anomal\\w*|unusual|abnormal|outliers?|weird|strange|stuck|spikes?)\\b");
	private static final Pattern AUTOMATION = Pattern.compile("\\b(automations?|rules?|triggered|fired)\\b");
	private static final Pattern SECURITY = Pattern.compile("\\b(security|alerts?|alarms?|intru\\w*|incidents?|leaks?|away mode|night mode)\\b");
	private static final Pattern HEALTH = Pattern.compile("\\b(health|healthy|system status|working|broken|problems?|issues?|errors?)\\b");
	/** Gas incidents are the gas alert records, never a sensor average or a security event. */
	private static final Pattern GAS_WORD = Pattern.compile("\\b(gas|smoke|lpg|mq-?2)\\b");
	private static final Pattern INCIDENT = Pattern.compile("\\b(incidents?|alerts?|alarms?|leaks?|leakages?)\\b");
	private static final Pattern BARE_INCIDENT = Pattern.compile("\\bincidents?\\b");
	private static final Pattern NOT_GAS_INCIDENT = Pattern.compile("\\b(security|intru\\w*|motion|away mode|night mode|offline)\\b");
	/** "last five", "latest 3", "most recent ten" (but not "last 24 hours"). */
	private static final Pattern LAST_N = Pattern.compile(
			"\\b(?:last|latest|recent|newest|past)\\s+(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|twenty)\\b(?!\\s+(?:hours?|days?|weeks?|months?|minutes?))");
	private static final Pattern SINGLE = Pattern.compile("\\b(the )?(last|latest|most recent|previous|newest) (gas )?(incident|alert|alarm|leak)\\b");
	private static final List<String> NUMBER_WORDS = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");
	/** A question about the LED history ("when was the gas LED last turned on?"), not a command to change it. */
	private static final Pattern LED = Pattern.compile("\\b(leds?|indicators?|lamp)\\b");
	private static final Pattern LED_HISTORY = Pattern.compile("\\b(when|last|latest|history|commands?|turned|switched|times?|ever|recently)\\b");
	private static final Pattern IMPERATIVE = Pattern.compile("^(please )?(turn|switch|set|put|make|enable|disable|activate|deactivate|force)\\b");
	private static final Pattern CONNECTIVITY = Pattern.compile("\\b(online|offline|connected|disconnected|connection|connectivity)\\b");
	/** "summarize the recent activity of ESP-1", "what happened today": an overview across sensors, motion and events. */
	private static final Pattern SUMMARY = Pattern.compile("\\b(summar\\w*|overview|recap|what happened|recent activity|what has .* been doing)\\b");
	private static final Pattern REPORT = Pattern.compile("\\b(generate|create|make|build|prepare|give me)\\b.*\\breport\\b|\\breport\\b.*\\b(generate|create|make|build|prepare)\\b");
	/** "the day before", "the week before", "previous period", "before that": compare with the period just before. */
	private static final Pattern BEFORE_THAT = Pattern.compile(
			"\\b(the (day|week|month) before|before that|previous (day|period)|prior (day|week|period)|the (\\d+ )?(hours|days|weeks) before)\\b");
	private static final Pattern HOW_TO = Pattern.compile("^(how (do|can|should|would) (i|we|you)|how to|what does|what is an? |explain how|why does|why do|can i|is it possible)\\b");
	private static final Pattern NEGATED = Pattern.compile("\\b(don't|dont|do not|never|no need to|without)\\b");
	private static final Pattern FOLLOW_UP = Pattern.compile("^(and|what about|how about|same|also|compare (it|that|this)|and for|for)\\b|\\b(it|that|this one|same thing)\\b");

	private QueryPlanner() {
	}

	/**
	 * @param deviceNames the caller's devices (names only), to recognise "kitchen" in "kitchen temperature"
	 * @param previous    what the conversation was about, or {@link Focus#EMPTY}
	 */
	public static Plan plan(String question, Collection<String> deviceNames, Focus previous, ObjectMapper mapper) {
		String q = " " + question.toLowerCase(Locale.ROOT).replaceAll("[?!,;]", " ").replaceAll("\\s+", " ").trim() + " ";
		Focus prev = previous == null ? Focus.EMPTY : previous;

		List<String> metrics = new ArrayList<>();
		METRICS.forEach((m, p) -> {
			if (p.matcher(q).find()) {
				metrics.add(m);
			}
		});
		List<String> periods = periodsIn(q);
		List<String> dates = new ArrayList<>();
		Matcher dm = ISO_DATE.matcher(q);
		while (dm.find()) {
			dates.add(dm.group(1));
		}
		String device = null;
		for (String name : deviceNames) {
			if (name.length() >= 3 && q.contains(name.toLowerCase(Locale.ROOT))) {
				device = name;
				break;
			}
		}

		boolean followUp = (prev.metric() != null || prev.period() != null) && (FOLLOW_UP.matcher(q.trim()).find() || q.trim().split(" ").length <= 4);
		boolean motion = MOTION.matcher(q).find() || (followUp && metrics.isEmpty() && "motion".equals(prev.metric()));
		if (metrics.isEmpty() && followUp && prev.metric() != null && !"motion".equals(prev.metric())) {
			metrics.add(prev.metric());
		}
		if (device == null && followUp) {
			device = prev.device();
		}
		String period = periods.isEmpty() ? null : periods.get(0);
		String from = null;
		String to = null;
		if (!dates.isEmpty()) {
			period = "custom";
			from = dates.get(0);
			to = dates.size() > 1 ? dates.get(1) : dates.get(0);
		}

		List<PlannedCall> calls = new ArrayList<>();
		String metric = metrics.size() == 1 ? metrics.get(0) : null;
		if (HOW_TO.matcher(q.trim()).find() && metrics.isEmpty() && periods.isEmpty() && dates.isEmpty()) {
			return new Plan(List.of(), prev, false);       // "how do I pair a device?" is for the help text, not for a query
		}
		boolean beforeThat = BEFORE_THAT.matcher(q).find();
		boolean compare = (COMPARE.matcher(q).find() || beforeThat)
				&& (periods.size() >= 2 || (periods.size() == 1 && (followUp || prev.period() != null || beforeThat)) || beforeThat);

		// A report is stored, so only an actual request creates one: not "how do I create a weekly report?" or "don't make a report".
		if (REPORT.matcher(q).find() && !HOW_TO.matcher(q.trim()).find() && !NEGATED.matcher(q).find()) {
			String kind = q.contains("month") ? "monthly" : q.contains("week") ? "weekly" : dates.isEmpty() ? "daily" : "custom";
			Map<String, Object> a = args("kind", kind, "device", device);
			if ("custom".equals(kind)) {
				a.put("from", from);
				a.put("to", to);
			}
			calls.add(call(mapper, "create_report", a));
			return new Plan(calls, prev, false);
		}

		// Exact historical records: answered from their own tables, never from sensor averages or similarity search.
		String trimmed = q.trim();
		if (LED.matcher(q).find() && LED_HISTORY.matcher(q).find() && !IMPERATIVE.matcher(trimmed).find()) {
			String action = GAS_WORD.matcher(q).find() ? "set_gas_led" : Pattern.compile("\\b(pir|motion)\\b").matcher(q).find() ? "set_pir_led" : null;
			String value = Pattern.compile("\\b(turned|switched|set|forced) on\\b|\\bon\\b").matcher(q).find() ? "on"
					: Pattern.compile("\\b(turned|switched|set) off\\b").matcher(q).find() ? "off"
							: Pattern.compile("\\b(auto|automatic)\\b").matcher(q).find() ? "auto" : null;
			Map<String, Object> a = withRange(args("action", action, "value", value, "device", device), period, from, to);
			a.put("limit", 5);
			calls.add(call(mapper, "get_command_history", a));
			return new Plan(calls, prev.merge(new Focus(null, period, from, to, device)), followUp);
		}
		boolean gasIncident = INCIDENT.matcher(q).find() && GAS_WORD.matcher(q).find()
				|| BARE_INCIDENT.matcher(q).find() && !NOT_GAS_INCIDENT.matcher(q).find();
		if (gasIncident && !compare) {
			Map<String, Object> a = withRange(args("device", device), period, from, to);
			a.put("limit", incidentLimit(q));
			calls.add(call(mapper, "get_gas_incidents", a));
			if (TREND.matcher(q).find() && period != null && !BARE_INCIDENT.matcher(q).find()) {
				calls.add(call(mapper, "get_sensor_trend", withRange(args("metric", "gas", "device", device), period, from, to)));
			}
			return new Plan(calls, prev.merge(new Focus("gas", period, from, to, device)), followUp);
		}
		if (SUMMARY.matcher(q).find() && metrics.isEmpty() && !compare) {
			String p = period != null ? period : "last_24h";
			calls.add(call(mapper, "get_sensor_statistics", withRange(args("device", device), p, from, to)));
			calls.add(call(mapper, "get_motion_activity", withRange(args("device", device), p, from, to)));
			calls.add(call(mapper, "get_security_events", withRange(args("device", device), p, from, to)));
			return new Plan(calls, prev.merge(new Focus(null, p, from, to, device)), followUp);
		}
		if (CONNECTIVITY.matcher(q).find() && metrics.isEmpty() && !motion) {
			calls.add(call(mapper, "get_device_status", withRange(args("device", device), period == null ? "last_24h" : period, from, to)));
			if (HEALTH.matcher(q).find()) {
				calls.add(call(mapper, "get_system_health", new TreeMap<>()));
			}
			return new Plan(calls, prev.merge(new Focus(null, period, from, to, device)), followUp);
		}

		if (compare && !motion) {
			String a;
			String b;
			if (periods.size() >= 2) {
				a = periods.get(0);
				b = periods.get(1);
			} else if (periods.size() == 1 && beforeThat) {
				a = periods.get(0);                      // "yesterday and the day before": A vs the period just before it
				b = "previous";
			} else if (periods.size() == 1 && prev.period() != null && !prev.period().equals(periods.get(0))) {
				a = prev.period();                       // "compare it with yesterday": A = what we talked about
				b = periods.get(0);
			} else {
				a = periods.isEmpty() ? (prev.period() != null ? prev.period() : "today") : periods.get(0);
				b = "previous";
			}
			if ("custom".equals(a)) {
				a = "today";
			}
			calls.add(call(mapper, "compare_periods", args("metric", metric, "period", a, "compare_with", "custom".equals(b) ? "previous" : b, "device", device)));
			return new Plan(calls, new Focus(metric, a, null, null, device), followUp);
		}

		boolean historical = period != null && !"last_hour".equals(period);
		boolean current = CURRENT.matcher(q).find() && !historical;
		if (!metrics.isEmpty() && !motion || (current && !motion)) {
			if (period == null && followUp && prev.period() != null && !current) {
				period = prev.period();
				from = prev.from();
				to = prev.to();
				historical = !"last_hour".equals(period);
			}
			if (!historical || current) {
				calls.add(call(mapper, "get_current_readings", args("device", device)));
			} else if (TREND.matcher(q).find() && metric != null) {
				calls.add(call(mapper, "get_sensor_trend", withRange(args("metric", metric, "device", device), period, from, to)));
			} else {
				calls.add(call(mapper, "get_sensor_statistics", withRange(args("metric", metric, "device", device), period, from, to)));
				if (metric != null && TREND.matcher(q).find()) {
					calls.add(call(mapper, "get_sensor_trend", withRange(args("metric", metric, "device", device), period, from, to)));
				}
			}
		} else if (STATS.matcher(q).find() && period != null) {
			calls.add(call(mapper, "get_sensor_statistics", withRange(args("device", device), period, from, to)));
		}
		if (motion) {
			String p = period != null ? period : followUp && prev.period() != null ? prev.period() : "today";
			calls.add(call(mapper, "get_motion_activity", withRange(args("device", device), p, from, to)));
			metric = "motion";
		}
		if (ANOMALY.matcher(q).find()) {
			calls.add(call(mapper, "get_anomalies", withRange(args("device", device), period == null ? "last_7_days" : period, from, to)));
		}
		if (SECURITY.matcher(q).find()) {
			calls.add(call(mapper, "get_security_events", withRange(args("device", device), period == null ? "last_7_days" : period, from, to)));
		}
		if (AUTOMATION.matcher(q).find() && !q.contains("suggest") && !q.contains("create a rule") && !q.contains("make a rule")) {
			calls.add(call(mapper, "get_automation_history", withRange(args("device", device), period == null ? "last_7_days" : period, from, to)));
		}
		if (HEALTH.matcher(q).find()) {
			calls.add(call(mapper, "get_system_health", new TreeMap<>()));
		}
		if (DEVICES.matcher(q).find() && calls.isEmpty()) {
			calls.add(call(mapper, "get_device_status", withRange(args("device", device), period == null ? "last_7_days" : period, from, to)));
		}
		List<PlannedCall> capped = calls.size() > MAX_CALLS ? calls.subList(0, MAX_CALLS) : calls;
		Focus focus = new Focus(metric, period, from, to, device);
		return new Plan(List.copyOf(capped), prev.merge(focus), followUp);
	}

	/** 1 for "the last incident", N for "the last five incidents", otherwise 10. */
	static int incidentLimit(String q) {
		Matcher m = LAST_N.matcher(q);
		if (m.find()) {
			String n = m.group(1);
			int v = n.chars().allMatch(Character::isDigit) ? Integer.parseInt(n) : "twenty".equals(n) ? 20 : NUMBER_WORDS.indexOf(n);
			return Math.max(1, Math.min(20, v));
		}
		return SINGLE.matcher(q).find() && !q.contains("incidents") ? 1 : 10;
	}

	/** Period presets in the order they appear in the text. */
	static List<String> periodsIn(String q) {
		TreeMap<Integer, String> found = new TreeMap<>();
		String rest = q;
		for (Map.Entry<String, String> e : PERIODS.entrySet()) {
			Matcher m = Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(rest);
			while (m.find()) {
				found.putIfAbsent(m.start(), e.getValue());
				// blank it so "last week" inside "last weekend" or a shorter phrase cannot match the same text again
				rest = rest.substring(0, m.start()) + " ".repeat(m.end() - m.start()) + rest.substring(m.end());
				m = Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "\\b").matcher(rest);
			}
		}
		List<String> out = new ArrayList<>();
		for (String p : found.values()) {
			if (!out.contains(p)) {
				out.add(p);
			}
		}
		return out;
	}

	private static Map<String, Object> args(Object... kv) {
		Map<String, Object> m = new TreeMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			if (kv[i + 1] != null) {
				m.put((String) kv[i], kv[i + 1]);
			}
		}
		return m;
	}

	private static Map<String, Object> withRange(Map<String, Object> a, String period, String from, String to) {
		if (period != null) {
			a.put("period", period);
		}
		if ("custom".equals(period)) {
			a.put("from", from);
			a.put("to", to);
		}
		return a;
	}

	private static PlannedCall call(ObjectMapper mapper, String tool, Map<String, Object> args) {
		return new PlannedCall(tool, mapper.writeValueAsString(args));
	}
}
