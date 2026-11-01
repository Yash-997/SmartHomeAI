P// Turns a received MQTT payload into CommandFields for validateCommand(). Separate from NetLink.cpp so the exact
// parsing can be unit-tested on a PC against the backend's real wire format (ArduinoJson is header-only).
#pragma once

#include <ArduinoJson.h>

#include <string.h>

#include "Logic.h"

/** Owns the strings that `fields` points into. Do not copy. */
struct ParsedCommand {
  char id[40];
  char action[24];
  char value[12];
  CommandFields fields;

  ParsedCommand() {
    id[0] = action[0] = value[0] = '\0';
  }
  ParsedCommand(const ParsedCommand&) = delete;
  ParsedCommand& operator=(const ParsedCommand&) = delete;
};

// A string field that is missing, not a string, or too long for its buffer is left null, which makes validation
// fail; it is never truncated into something that might look valid.
inline const char* copyString(JsonVariantConst v, char* buf, size_t cap) {
  if (!v.is<const char*>()) return nullptr;
  const char* s = v.as<const char*>();
  if (!s || strlen(s) >= cap) return nullptr;
  strcpy(buf, s);
  return buf;
}

/** Returns false if the payload is not a JSON object at all (nothing to answer). */
inline bool parseCommandJson(const uint8_t* payload, size_t length, ParsedCommand& out) {
  JsonDocument doc;
  if (deserializeJson(doc, payload, length) != DeserializationError::Ok) return false;
  if (!doc.is<JsonObject>()) return false;

  out.fields.id = copyString(doc["id"], out.id, sizeof out.id);
  out.fields.action = copyString(doc["action"], out.action, sizeof out.action);
  out.fields.value = copyString(doc["value"], out.value, sizeof out.value);

  JsonVariantConst exp = doc["expiresAt"];
  out.fields.hasExpiry = exp.is<long long>();     // false for strings, floats, null, missing
  out.fields.expiresAt = out.fields.hasExpiry ? (int64_t)exp.as<long long>() : 0;
  return true;
}
