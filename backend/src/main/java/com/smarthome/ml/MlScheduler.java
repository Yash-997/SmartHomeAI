package com.smarthome.ml;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The two slow background jobs. Both only hand work to {@link MlDispatcher}; neither runs on the scheduling thread.
 * Training is checked hourly and retrains a model about once a day when enough new data has arrived; it is never
 * triggered by an individual telemetry message.
 */
@Component
public class MlScheduler {

	private final MlDispatcher dispatcher;
	private final AnomalyScanner scanner;
	private final OccupancyService occupancy;
	private final MlProperties props;
	private final Clock clock;

	public MlScheduler(MlDispatcher dispatcher, AnomalyScanner scanner, OccupancyService occupancy, MlProperties props, Clock clock) {
		this.dispatcher = dispatcher;
		this.scanner = scanner;
		this.occupancy = occupancy;
		this.props = props;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.ml.anomaly.scan-ms:60000}", initialDelayString = "${app.ml.anomaly.scan-ms:60000}")
	public void scan() {
		if (props.anomaly().scanEnabled()) {
			dispatcher.submit(() -> scanner.scanAll(clock.instant()));
		}
	}

	@Scheduled(fixedDelayString = "${app.ml.training.auto-check-ms:3600000}", initialDelayString = "${app.ml.training.auto-check-ms:3600000}")
	public void train() {
		if (props.training().autoEnabled()) {
			dispatcher.submit(() -> occupancy.autoTrainAll(clock.instant()));
		}
	}
}
