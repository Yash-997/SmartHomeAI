package com.smarthome.ml;

/** What kind of problem was found. */
public enum AnomalyKind {
	/** A reading far from what is normal for this sensor at this time of day. */
	STATISTICAL_OUTLIER,
	/** A value no real sensor of this type should produce (or one the backend refused to store). */
	OUT_OF_RANGE,
	/** The same exact value over and over, as a frozen sensor would report. */
	STUCK_VALUE,
	/** The device keeps reporting but a sensor value is absent. */
	MISSING_DATA,
	/** The device has stopped reporting. */
	STALE_DATA
}
