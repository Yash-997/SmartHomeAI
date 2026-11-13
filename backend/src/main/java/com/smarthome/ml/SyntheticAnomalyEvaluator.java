package com.smarthome.ml;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.smarthome.ml.SyntheticGenerator.Reading;
import com.smarthome.ml.SyntheticGenerator.Scenario;
import com.smarthome.ml.SyntheticGenerator.ScenarioWindow;

/**
 * Replays the last days of a SYNTHETIC dataset, minute by minute, through the existing anomaly detectors exactly as the
 * live scanner uses them (same rules, same thresholds, same recent-window size, a baseline learned from the previous
 * week), and scores the outcome against the labelled scenarios.
 *
 * <p>Nothing is tuned to pass: a scenario counts as detected only if the rule meant for it fires inside the scenario (or
 * shortly after, for "silent device" which by definition needs time to notice), and every finding during normal minutes
 * is counted as a false positive.
 */
public final class SyntheticAnomalyEvaluator {

	/** A finding may come this long after a scenario ends and still belong to it (e.g. the stale check needs minutes). */
	static final Duration GRACE = Duration.ofMinutes(30);

	public record ScenarioResult(Scenario scenario, String description, Instant from, Instant to, String expectedRule, boolean detected,
			Long delayMinutes, String detectedAs) {
	}

	public record Evaluation(Instant from, Instant to, double normalHours, List<ScenarioResult> scenarios, Map<String, Integer> falsePositiveEpisodes,
			int totalFalsePositiveEpisodes, String method) {
	}

	private record Episode(AnomalyKind kind, String scope, Instant start) {
	}

	private SyntheticAnomalyEvaluator() {
	}

	private static final Map<Scenario, Set<AnomalyKind>> EXPECTED = new EnumMap<>(Map.of(
			Scenario.FROZEN_SENSOR, Set.of(AnomalyKind.STUCK_VALUE),
			Scenario.GAS_SATURATION, Set.of(AnomalyKind.OUT_OF_RANGE),
			Scenario.MISSING_VALUES, Set.of(AnomalyKind.MISSING_DATA),
			Scenario.DISCONNECTION, Set.of(AnomalyKind.STALE_DATA),
			Scenario.UNUSUAL_MOTION, Set.of(AnomalyKind.UNUSUAL_ACTIVITY)));

	/** @param model the occupancy model trained on this dataset (for the unusual-motion rule), or null */
	public static Evaluation evaluate(SyntheticGenerator.Dataset ds, OccupancyModel model, MlProperties props) {
		MlProperties.Anomaly cfg = props.anomaly();
		Instant firstScenario = ds.scenarios().stream().map(ScenarioWindow::from).min(Instant::compareTo).orElse(ds.end());
		Instant from = firstScenario.minus(Duration.ofDays(1)).isBefore(ds.start().plus(Duration.ofDays(cfg.baselineDays())))
				? ds.start().plus(Duration.ofDays(cfg.baselineDays())) : firstScenario.minus(Duration.ofDays(1));
		Instant to = ds.end();

		Map<Long, Reading> byMinute = new HashMap<>();
		for (Reading r : ds.readings()) {
			byMinute.put(r.at().getEpochSecond() / 60, r);
		}
		List<Reading> sorted = ds.readings();
		Deque<SensorSample> recent = new ArrayDeque<>();   // newest first, at most cfg.recentWindow()
		// Seed the recent window with what came just before the evaluation period.
		for (Reading r : sorted) {
			if (r.at().isBefore(from)) {
				recent.addFirst(sample(r));
				if (recent.size() > cfg.recentWindow()) {
					recent.removeLast();
				}
			}
		}
		Instant lastAt = recent.isEmpty() ? null : recent.peekFirst().at();

		List<Episode> episodes = new ArrayList<>();
		Map<String, Instant> lastSeen = new HashMap<>();        // kind:scope -> last minute it fired (to merge episodes)
		Baselines baselines = null;
		long baselineDay = -1;
		java.time.ZoneId zone = model == null ? ds.zone() : java.time.ZoneId.of(model.timeZone());

		for (Instant t = from; t.isBefore(to); t = t.plusSeconds(60)) {
			long day = t.getEpochSecond() / 86_400;
			if (day != baselineDay) {                           // the live scanner refreshes its baseline periodically too
				Instant bFrom = t.minus(Duration.ofDays(cfg.baselineDays()));
				Instant bTo = t.minus(Duration.ofMinutes(cfg.baselineLagMinutes()));
				List<SensorSample> history = new ArrayList<>();
				for (Reading r : sorted) {
					if (!r.at().isBefore(bFrom) && r.at().isBefore(bTo)) {
						history.add(sample(r));
					}
				}
				baselines = Baselines.build(history, cfg);
				baselineDay = day;
			}
			List<Optional<Finding>> found = new ArrayList<>();
			Reading r = byMinute.get(t.getEpochSecond() / 60);
			if (r != null) {
				SensorSample s = sample(r);
				SensorSample previous = recent.peekFirst();
				recent.addFirst(s);
				if (recent.size() > cfg.recentWindow()) {
					recent.removeLast();
				}
				lastAt = s.at();
				for (Metric m : Metric.values()) {
					Optional<Finding> bad = AnomalyDetectors.plausibility(m, s, cfg);
					if (bad.isEmpty()) {
						bad = AnomalyDetectors.outlier(m, s, baselines.lookup(m, s.at()), cfg);
					}
					found.add(bad);
					found.add(AnomalyDetectors.suddenChange(m, previousWith(m, recent, s, previous), s, baselines.delta(m), cfg));
				}
				if (model != null && Boolean.TRUE.equals(r.motion())) {
					ZonedDateTime local = r.at().atZone(zone);
					found.add(AnomalyDetectors.unusualActivity(local, r.at(), model.probability(local), model.observedRate(local), model.support(local), cfg));
				}
			}
			if (lastAt != null) {
				found.add(AnomalyDetectors.stale(lastAt, t, cfg));
			}
			List<SensorSample> window = new ArrayList<>(recent);
			for (Metric m : Metric.values()) {
				found.add(AnomalyDetectors.stuck(m, window, cfg));
				found.add(AnomalyDetectors.missing(m, window, cfg));
			}
			for (Optional<Finding> f : found) {
				if (f.isEmpty()) {
					continue;
				}
				String key = f.get().kind() + ":" + f.get().scope();
				Instant prev = lastSeen.get(key);
				if (prev == null || Duration.between(prev, t).toMinutes() > 2) {
					episodes.add(new Episode(f.get().kind(), f.get().scope(), t));   // a new episode of this problem
				}
				lastSeen.put(key, t);
			}
		}

		List<ScenarioResult> results = new ArrayList<>();
		for (ScenarioWindow w : ds.scenarios()) {
			Set<AnomalyKind> expected = EXPECTED.get(w.scenario());
			Episode hit = episodes.stream()
					.filter(e -> expected.contains(e.kind()) && !e.start().isBefore(w.from()) && e.start().isBefore(w.to().plus(GRACE)))
					.findFirst().orElse(null);
			results.add(new ScenarioResult(w.scenario(), w.description(), w.from(), w.to(), String.join(", ", expected.stream().map(Enum::name).toList()),
					hit != null, hit == null ? null : Duration.between(w.from(), hit.start()).toMinutes(),
					hit == null ? null : hit.kind() + " (" + hit.scope() + ")"));
		}
		Map<String, Integer> falsePositives = new LinkedHashMap<>();
		int total = 0;
		for (Episode e : episodes) {
			boolean duringScenario = ds.scenarios().stream()
					.anyMatch(w -> !e.start().isBefore(w.from()) && e.start().isBefore(w.to().plus(GRACE)));
			if (!duringScenario) {
				falsePositives.merge(e.kind().name(), 1, Integer::sum);
				total++;
			}
		}
		long scenarioMinutes = ds.scenarios().stream().mapToLong(w -> Duration.between(w.from(), w.to().plus(GRACE)).toMinutes()).sum();
		double normalHours = (Duration.between(from, to).toMinutes() - scenarioMinutes) / 60.0;
		return new Evaluation(from, to, normalHours, results, falsePositives, total,
				"Replayed minute by minute through the existing anomaly rules with the production thresholds; baseline from the previous "
						+ cfg.baselineDays() + " days; a finding outside every labelled scenario counts as a false positive.");
	}

	private static SensorSample sample(Reading r) {
		return new SensorSample(r.at(), r.temperature(), r.humidity(), r.gasRaw());
	}

	/** The newest earlier reading with a value for this sensor (recent is newest first and already contains s). */
	private static SensorSample previousWith(Metric m, Deque<SensorSample> recent, SensorSample s, SensorSample fallback) {
		boolean past = false;
		for (SensorSample x : recent) {
			if (past && m.of(x) != null) {
				return x;
			}
			if (x == s) {
				past = true;
			}
		}
		return null;
	}
}
