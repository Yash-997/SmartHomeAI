package com.smarthome.assistant;

import java.time.Instant;
import java.util.UUID;

/** One piece of searchable text built from real records: where it came from, what it says and when it was true. */
public record FactDoc(String sourceType, String sourceKey, UUID deviceUuid, String title, String text, Instant asOf) {
}
