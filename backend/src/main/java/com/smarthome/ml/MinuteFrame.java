package com.smarthome.ml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Telemetry resampled to one row per minute: the input of the anomaly model's feature pipeline. The real ESP32
 * publishes every 30 s and the synthetic generator once a minute, so resampling gives the model the same cadence
 * for both, and a model never depends on how often a device happens to report.
 *
 * <p>A value is the mean of that sensor's readings in the minute; null when the minute had messages but none carried
 * that sensor (a missing value is never a zero). Motion is true if any message in the minute saw motion. A minute
 * with no message at all has no frame, which is how gaps (a silent device) show up.
 */
public record MinuteFrame(Instant at, Double temperature, Double humidity, Double gas, Boolean motion, int messages) {

	public Double of(Metric m) {
		return switch (m) {
			case TEMPERATURE -> temperature;
			case HUMIDITY -> humidity;
			case GAS -> gas;
		};
	}

	/** Points in any order; frames come out oldest first, one per minute that had at least one message. */
	public static List<MinuteFrame> resample(List<TelemetryPoint> points) {
		List<TelemetryPoint> sorted = new ArrayList<>(points);
		sorted.sort((a, b) -> a.at().compareTo(b.at()));
		List<MinuteFrame> out = new ArrayList<>();
		int i = 0;
		while (i < sorted.size()) {
			long minute = Math.floorDiv(sorted.get(i).at().getEpochSecond(), 60);
			double t = 0, h = 0, g = 0;
			int nt = 0, nh = 0, ng = 0, messages = 0;
			Boolean motion = null;
			while (i < sorted.size() && Math.floorDiv(sorted.get(i).at().getEpochSecond(), 60) == minute) {
				TelemetryPoint p = sorted.get(i++);
				messages++;
				if (p.temperature() != null && Double.isFinite(p.temperature())) {
					t += p.temperature();
					nt++;
				}
				if (p.humidity() != null && Double.isFinite(p.humidity())) {
					h += p.humidity();
					nh++;
				}
				if (p.gas() != null) {
					g += p.gas();
					ng++;
				}
				if (p.motion() != null) {
					motion = (motion != null && motion) || p.motion();
				}
			}
			out.add(new MinuteFrame(Instant.ofEpochSecond(minute * 60), nt == 0 ? null : t / nt, nh == 0 ? null : h / nh, ng == 0 ? null : g / ng,
					motion, messages));
		}
		return out;
	}
}
