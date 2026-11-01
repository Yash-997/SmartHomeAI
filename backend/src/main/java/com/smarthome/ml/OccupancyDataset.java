package com.smarthome.ml;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns raw motion readings into labelled training examples ("slots").
 *
 * <p>The rules that keep this honest:
 * <ul>
 *   <li>A slot is labelled occupied if the PIR fired at least once in it, otherwise unoccupied.</li>
 *   <li>A slot with too few readings is <b>dropped</b>, not labelled "unoccupied". Silence from a device that was
 *       offline says nothing about the room, so absent data never becomes a negative example.</li>
 *   <li>Features come from the slot's local time in the model's time zone (daylight saving included).</li>
 * </ul>
 */
public final class OccupancyDataset {

	/** One labelled slot. {@code x} are the features of its midpoint; {@code cell} is the weekday-and-hour index. */
	public record Bucket(long startEpoch, boolean occupied, double[] x, int cell, LocalDate day) {
	}

	/** Slots in chronological order plus honest bookkeeping about what was (not) usable. */
	public record Built(List<Bucket> buckets, int samples, int sparseBucketsDropped, int distinctDays, int positives,
			Instant first, Instant last) {
	}

	private OccupancyDataset() {
	}

	public static Built build(List<MotionSample> samples, ZoneId zone, int bucketMinutes, int minReadingsPerBucket) {
		if (bucketMinutes <= 0 || 60 % bucketMinutes != 0) {
			throw new IllegalArgumentException("bucketMinutes must divide 60");
		}
		long width = bucketMinutes * 60L;
		// start -> {readings, motion readings}
		TreeMap<Long, int[]> slots = new TreeMap<>();
		for (MotionSample s : samples) {
			long start = Math.floorDiv(s.at().getEpochSecond(), width) * width;
			int[] c = slots.computeIfAbsent(start, k -> new int[2]);
			c[0]++;
			if (s.motion()) {
				c[1]++;
			}
		}
		List<Bucket> buckets = new ArrayList<>();
		Set<LocalDate> days = new HashSet<>();
		int dropped = 0;
		int positives = 0;
		for (var e : slots.entrySet()) {
			int[] c = e.getValue();
			if (c[0] < minReadingsPerBucket) {
				dropped++;
				continue;
			}
			ZonedDateTime mid = Instant.ofEpochSecond(e.getKey() + width / 2).atZone(zone);
			boolean occupied = c[1] > 0;
			if (occupied) {
				positives++;
			}
			days.add(mid.toLocalDate());
			buckets.add(new Bucket(e.getKey(), occupied, OccupancyFeatures.of(mid), OccupancyFeatures.cell(mid), mid.toLocalDate()));
		}
		Instant first = samples.isEmpty() ? null : samples.stream().map(MotionSample::at).min(Instant::compareTo).orElse(null);
		Instant last = samples.isEmpty() ? null : samples.stream().map(MotionSample::at).max(Instant::compareTo).orElse(null);
		return new Built(buckets, samples.size(), dropped, days.size(), positives, first, last);
	}
}
