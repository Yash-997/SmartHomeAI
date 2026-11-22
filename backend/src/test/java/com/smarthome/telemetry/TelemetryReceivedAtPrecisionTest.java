package com.smarthome.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Regression test: received_at is a TIMESTAMP(6) column. A clock reading with sub-microsecond digits used to be stored
 * rounded UP by the database, i.e. after the moment it was received, so a period ending at a "now" read in the same clock
 * tick missed the newest readings (AssistantIntelligenceTests failed intermittently on Windows, whose clock advances about
 * once a millisecond).
 */
class TelemetryReceivedAtPrecisionTest {

	@Test
	void theStoredReceiveTimeIsNeverLaterThanTheClockAndFitsTheColumn() {
		// The exact instant from a captured failure: stored as .583281, after a "now" of .5832806 read in the same tick.
		Instant tick = Instant.parse("2026-11-21T18:40:20.583280600Z");
		DeviceRepository devices = mock(DeviceRepository.class);
		TelemetryRepository readings = mock(TelemetryRepository.class);
		Device d = new Device("esp32-precision", UUID.randomUUID(), "Precision", tick.minusSeconds(60), "hash");
		when(devices.findByDeviceId("esp32-precision")).thenReturn(Optional.of(d));
		TelemetryIngestService ingest = new TelemetryIngestService(devices, readings, JsonMapper.builder().build(),
				Clock.fixed(tick, ZoneOffset.UTC), mock(ApplicationEventPublisher.class));

		TelemetryIngestService.Result r = ingest.handle("home/devices/esp32-precision/telemetry",
				"{\"seq\":1,\"temperature\":26.0}".getBytes(StandardCharsets.UTF_8), false);

		assertEquals(TelemetryIngestService.Result.STORED, r);
		ArgumentCaptor<TelemetryReading> saved = ArgumentCaptor.forClass(TelemetryReading.class);
		verify(readings).save(saved.capture());
		Instant receivedAt = saved.getValue().getReceivedAt();
		assertFalse(receivedAt.isAfter(tick), "received " + receivedAt + " is after the clock " + tick);
		assertEquals(0, receivedAt.getNano() % 1_000, "whole microseconds: the database stores it unchanged, never rounded up");
		assertEquals(Instant.parse("2026-11-21T18:40:20.583280Z"), receivedAt);
		verify(readings).existsByDeviceUuidAndDedupeKeyAndReceivedAtAfter(any(), any(), any());
	}
}
