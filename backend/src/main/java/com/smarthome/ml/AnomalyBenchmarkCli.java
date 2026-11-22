package com.smarthome.ml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline training and evaluation of the anomaly model on SYNTHETIC data, in plain Java (no Spring, no database):
 * generates the labelled dataset, trains the occupancy model the unusual-motion rule needs, runs {@link AnomalyBenchmark}
 * and writes three files:
 * <ul>
 *   <li>{@code anomaly-model.json}: the trained model (forest, preprocessing, threshold), loadable as {@link AnomalyModel}</li>
 *   <li>{@code anomaly-evaluation.json}: every number of the evaluation</li>
 *   <li>{@code anomaly-evaluation.md}: the same, readable</li>
 * </ul>
 * Arguments (all optional): output directory (default {@code target/ml-reports}), dataset seed (42), days (30), first
 * day (2026-10-12), time zone (Asia/Kolkata). The same arguments always give the same model and the same numbers.
 */
public final class AnomalyBenchmarkCli {

	private AnomalyBenchmarkCli() {
	}

	public static void main(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : "target/ml-reports");
		long seed = args.length > 1 ? Long.parseLong(args[1]) : 42;
		int days = args.length > 2 ? Integer.parseInt(args[2]) : 30;
		LocalDate firstDay = args.length > 3 ? LocalDate.parse(args[3]) : LocalDate.of(2026, 10, 12);
		ZoneId zone = ZoneId.of(args.length > 4 ? args[4] : "Asia/Kolkata");
		AnomalyBenchmark.Outcome o = run(out, seed, days, firstDay, zone, MlProperties.defaults());
		System.out.println(markdown(o.report()));
		System.out.println("Wrote " + out.toAbsolutePath());
	}

	public static AnomalyBenchmark.Outcome run(Path out, long seed, int days, LocalDate firstDay, ZoneId zone, MlProperties props) throws Exception {
		Instant start = firstDay.atStartOfDay(zone).toInstant();
		SyntheticGenerator.Dataset ds = SyntheticGenerator.generate(start, days, zone, seed);
		OccupancyModel occupancy = SyntheticTrainer.train(ds, props.occupancy()).model();
		AnomalyBenchmark.Outcome o = AnomalyBenchmark.run(ds, occupancy, props);
		ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
		Files.createDirectories(out);
		Files.writeString(out.resolve("anomaly-model.json"), mapper.writeValueAsString(o.model()));
		Files.writeString(out.resolve("anomaly-evaluation.json"), mapper.writeValueAsString(o.report()));
		Files.writeString(out.resolve("anomaly-evaluation.md"), markdown(o.report()));
		return o;
	}

	/** The evaluation as a Markdown report. */
	public static String markdown(AnomalyBenchmark.Report r) {
		StringBuilder b = new StringBuilder();
		b.append("# Anomaly detection evaluation (SYNTHETIC data)\n\n");
		b.append("> ").append(r.notice()).append("\n\n");
		b.append("Generator ").append(r.generatorVersion()).append(", dataset seed ").append(r.datasetSeed()).append(", forest seed ")
				.append(r.forestSeed()).append(". ").append(r.method()).append("\n\n");
		AnomalyBenchmark.SplitInfo s = r.split();
		b.append("| Part | From | To | Size |\n|---|---|---|---|\n");
		b.append(String.format(Locale.ROOT, "| Train (normal only) | %s | %s | %d minutes with readings |%n", s.trainFrom(), s.trainTo(), s.trainFrames()));
		b.append(String.format(Locale.ROOT, "| Validation | %s | %s | %d minutes |%n", s.validationFrom(), s.validationTo(), s.validationMinutes()));
		b.append(String.format(Locale.ROOT, "| Test | %s | %s | %d minutes |%n%n", s.testFrom(), s.testTo(), s.testMinutes()));
		AnomalyBenchmark.Candidate c = r.chosen();
		b.append(String.format(Locale.ROOT, "**Chosen on validation** (%d candidates): %d trees, subsample %d, threshold = %.4f quantile of training scores "
				+ "(%.4f), %d consecutive minute(s). Validation F1 %s, %.3f false alarms/hour. %d features. Training + tuning + evaluation took %d ms.%n%n",
				r.candidatesTried(), c.trees(), c.subsample(), c.quantile(), c.threshold(), c.consecutive(), c.validationF1(),
				c.validationFalseAlarmsPerHour(), r.featureNames().size(), r.trainingMillis()));
		section(b, "Test period (scored once)", r.test());
		section(b, "Validation period (used for tuning)", r.validation());
		return b.toString();
	}

	private static void section(StringBuilder b, String title, List<AnomalyBenchmark.DetectorResult> results) {
		b.append("## ").append(title).append("\n\n");
		b.append("| Detector | Precision | Recall | F1 | TP | FP | FN | TN | False alarms/hour | Windows detected | Mean latency (min) | Median latency (min) |\n");
		b.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
		for (AnomalyBenchmark.DetectorResult d : results) {
			var m = d.confusion();
			b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d | %d | %d | %d | %.3f (%d in %.1f h) | %d / %d | %s | %s |%n", d.detector(),
					d.precision(), d.recall(), d.f1(), m.truePositives(), m.falsePositives(), m.falseNegatives(), m.trueNegatives(),
					d.falseAlarmsPerHour(), d.falseAlarms(), d.normalHours(), d.windowsDetected(), d.windows(), d.meanLatencyMinutes(),
					d.medianLatencyMinutes()));
		}
		b.append("\n**By anomaly type** (minute recall / detected windows / mean latency in minutes)\n\n");
		b.append("| Type |");
		for (AnomalyBenchmark.DetectorResult d : results) {
			b.append(' ').append(d.detector()).append(" |");
		}
		b.append("\n|---|");
		results.forEach(d -> b.append("---|"));
		b.append('\n');
		for (int i = 0; i < results.get(0).byType().size(); i++) {
			b.append("| ").append(results.get(0).byType().get(i).scenario()).append(" |");
			for (AnomalyBenchmark.DetectorResult d : results) {
				AnomalyBenchmark.TypeResult t = d.byType().get(i);
				b.append(String.format(Locale.ROOT, " %.3f / %d of %d / %s |", t.minuteRecall(), t.detected(), t.windows(),
						t.meanLatencyMinutes() == null ? "-" : String.format(Locale.ROOT, "%.0f", t.meanLatencyMinutes())));
			}
			b.append('\n');
		}
		b.append('\n');
		AnomalyBenchmark.DetectorResult hybrid = results.get(results.size() - 1);
		b.append("Windows (").append(hybrid.detector()).append("):\n\n");
		for (AnomalyBenchmark.WindowResult w : hybrid.windowResults()) {
			b.append(String.format(Locale.ROOT, "- %s %s: %s%s, minute recall %.3f%n", w.from(), w.scenario(), w.detected() ? "detected" : "MISSED",
					w.detected() ? " after " + w.latencyMinutes() + " min" : "", w.minuteRecall()));
		}
		b.append('\n');
	}
}
