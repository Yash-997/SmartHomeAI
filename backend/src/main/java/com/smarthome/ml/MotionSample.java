package com.smarthome.ml;

import java.time.Instant;

/** One stored motion reading: when it arrived and whether the PIR fired since the previous report. Never null. */
public record MotionSample(Instant at, boolean motion) {

	/** Used by the JPQL constructor expression; the query already excludes null motion values. */
	public MotionSample(Instant at, Boolean motion) {
		this(at, Boolean.TRUE.equals(motion));
	}
}
