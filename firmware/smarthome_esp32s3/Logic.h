// Hardware-independent logic, kept free of Arduino headers so it can be unit-tested on a PC
// (see firmware/tests/logic_test.cpp).
#pragma once

#include <ctype.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

// ---------------------------------------------------------------------------------------------------------------------
// Telemetry JSON, exactly the schema the backend validates:
//   {"seq":N,"ts":epochSeconds,"temperature":C,"humidity":P,"motion":bool,"gasRaw":ADC}
// Only seq is mandatory. A reading the device doesn't have is OMITTED (never sent as 0 or null), so the backend and
// UI show "no reading". No topic-level or payload-level device ID is added: identity comes from the authenticated
// MQTT username and the topic.
// ---------------------------------------------------------------------------------------------------------------------
struct TelemetryFields {
  uint64_t seq = 0;
  bool hasTs = false;
  int64_t ts = 0;  // epoch SECONDS
  bool hasTemp = false;
  float temperatureC = 0;
  bool hasHum = false;
  float humidityPct = 0;
  bool hasMotion = false;
  bool motion = false;
  bool hasGas = false;
  int gasRaw = 0;  // raw 12-bit ADC counts, never ppm
};

/** Writes the JSON into out. Returns the length, or -1 if it didn't fit. */
inline int buildTelemetryJson(char* out, size_t cap, const TelemetryFields& f) {
  size_t n = 0;
  auto add = [&](const char* fmt, auto... args) -> bool {
    int w = snprintf(out + n, cap - n, fmt, args...);
    if (w < 0 || (size_t)w >= cap - n) return false;
    n += (size_t)w;
    return true;
  };
  if (cap == 0) return -1;
  if (!add("{\"seq\":%llu", (unsigned long long)f.seq)) return -1;
  if (f.hasTs && !add(",\"ts\":%lld", (long long)f.ts)) return -1;
  if (f.hasTemp && !add(",\"temperature\":%.1f", (double)f.temperatureC)) return -1;
  if (f.hasHum && !add(",\"humidity\":%.1f", (double)f.humidityPct)) return -1;
  if (f.hasMotion && !add(",\"motion\":%s", f.motion ? "true" : "false")) return -1;
  if (f.hasGas && !add(",\"gasRaw\":%d", f.gasRaw)) return -1;
  if (!add("}")) return -1;
  return (int)n;
}

// ---------------------------------------------------------------------------------------------------------------------
// Pairing code: 8 letters/digits, typed with or without a dash or spaces, any case.
// ---------------------------------------------------------------------------------------------------------------------
/** Normalises to 8 upper-case alphanumerics (out must hold 9 bytes). Returns false if it isn't a plausible code. */
inline bool normalizePairingCode(const char* in, char* out) {
  size_t n = 0;
  for (; *in; in++) {
    unsigned char c = (unsigned char)*in;
    if (c == '-' || c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
    if (!isalnum(c) || n >= 8) return false;
    out[n++] = (char)toupper(c);
  }
  out[n] = '\0';
  return n == 8;
}

// ---------------------------------------------------------------------------------------------------------------------
// Exponential back-off with jitter: min, 2*min, 4*min ... capped at max, each +/-20%. reset() after a success.
// ---------------------------------------------------------------------------------------------------------------------
class Backoff {
 public:
  Backoff(uint32_t minMs, uint32_t maxMs) : min_(minMs), max_(maxMs), cur_(minMs) {}

  /** rnd is any 32-bit random number (esp_random() on the device). */
  uint32_t nextDelay(uint32_t rnd) {
    uint32_t base = cur_;
    cur_ = (cur_ > max_ / 2) ? max_ : cur_ * 2;
    uint32_t span = base / 5;                      // 20%
    if (span == 0) return base;
    int64_t jitter = (int64_t)(rnd % (2 * span + 1)) - (int64_t)span;
    return (uint32_t)((int64_t)base + jitter);
  }

  void reset() { cur_ = min_; }
  uint32_t current() const { return cur_; }

 private:
  uint32_t min_, max_, cur_;
};

// ---------------------------------------------------------------------------------------------------------------------
// Gas warning with hysteresis. Works on raw ADC counts. State only flips after `confirm` consecutive samples, so a
// single noisy reading can't toggle the LED.
// ---------------------------------------------------------------------------------------------------------------------
class GasWarning {
 public:
  GasWarning(int warnAt, int clearBelow, int confirm) : warn_(warnAt), clear_(clearBelow), confirm_(confirm) {}

  bool update(int raw) {
    if (!on_) {
      streak_ = (raw >= warn_) ? streak_ + 1 : 0;
      if (streak_ >= confirm_) { on_ = true; streak_ = 0; }
    } else {
      streak_ = (raw < clear_) ? streak_ + 1 : 0;
      if (streak_ >= confirm_) { on_ = false; streak_ = 0; }
    }
    return on_;
  }

  void reset() { on_ = false; streak_ = 0; }
  bool active() const { return on_; }

 private:
  int warn_, clear_, confirm_;
  bool on_ = false;
  int streak_ = 0;
};

// ---------------------------------------------------------------------------------------------------------------------
// One MQ-2 sample from a burst of ADC conversions: the MEDIAN of the conversions that succeeded. Failed conversions
// (ok[i] == false: the ADC was busy, e.g. ADC2 while Wi-Fi runs) are left out instead of being counted as 0, and the
// median ignores a few stray values (e.g. zeros from an ADC that has not settled) that would drag a mean down.
// At least half must succeed, otherwise there is no sample. minOut/maxOut give the spread, for diagnostics.
// ---------------------------------------------------------------------------------------------------------------------
inline bool medianValidReads(const int* values, const bool* ok, int n, int& out, int* minOut = nullptr, int* maxOut = nullptr) {
  int good[64];
  int g = 0;
  for (int i = 0; i < n && g < 64; i++) {
    if (!ok[i] || values[i] < 0 || values[i] > 4095) continue;
    good[g++] = values[i];
  }
  if (n <= 0 || g * 2 < n) return false;
  for (int i = 1; i < g; i++) {   // insertion sort: g is small
    int v = good[i], j = i - 1;
    while (j >= 0 && good[j] > v) { good[j + 1] = good[j]; j--; }
    good[j + 1] = v;
  }
  out = (g % 2) ? good[g / 2] : (good[g / 2 - 1] + good[g / 2]) / 2;
  if (minOut) *minOut = good[0];
  if (maxOut) *maxOut = good[g - 1];
  return true;
}

// ---------------------------------------------------------------------------------------------------------------------
// LED self-test (serial command "ledtest"). Feeds SIMULATED readings through the real gas warning logic: first a high
// value for highMs, then a low value for lowMs. Returns false when the test is over. Simulated values are never
// published as telemetry.
// ---------------------------------------------------------------------------------------------------------------------
inline bool ledTestValue(uint32_t elapsedMs, uint32_t highMs, uint32_t lowMs, int highRaw, int lowRaw, int& out) {
  if (elapsedMs < highMs) { out = highRaw; return true; }
  if (elapsedMs < highMs + lowMs) { out = lowRaw; return true; }
  return false;
}

// ---------------------------------------------------------------------------------------------------------------------
// DHT22 frame decoding. The sensor sends 40 bits; each is a ~50 us LOW followed by a HIGH of ~26-28 us (0) or ~70 us
// (1). lowUs/highUs are the measured durations; a negative value means that pulse timed out. A bit is 1 when its HIGH
// is longer than its LOW, which tolerates clock differences between sensors. Bytes: humidity x10 (2), temperature x10
// (2, top bit = negative), checksum = low byte of the sum of the first four.
// ---------------------------------------------------------------------------------------------------------------------
enum class DhtResult { Ok, Timeout, Checksum, OutOfRange, AllZero };

inline const char* dhtResultName(DhtResult r) {
  switch (r) {
    case DhtResult::Ok: return "ok";
    case DhtResult::Timeout: return "timeout";
    case DhtResult::Checksum: return "checksum";
    case DhtResult::OutOfRange: return "out of range";
    case DhtResult::AllZero: return "all zero";
  }
  return "?";
}

inline DhtResult dht22Decode(const int32_t* lowUs, const int32_t* highUs, int bits, float& tempC, float& humPct) {
  if (bits != 40) return DhtResult::Timeout;
  uint8_t b[5] = {0, 0, 0, 0, 0};
  for (int i = 0; i < 40; i++) {
    if (lowUs[i] < 0 || highUs[i] < 0) return DhtResult::Timeout;
    b[i / 8] = (uint8_t)((b[i / 8] << 1) | (highUs[i] > lowUs[i] ? 1 : 0));
  }
  if ((uint8_t)(b[0] + b[1] + b[2] + b[3]) != b[4]) return DhtResult::Checksum;
  if ((b[0] | b[1] | b[2] | b[3] | b[4]) == 0) return DhtResult::AllZero;   // a line stuck LOW decodes like this
  const float h = ((b[0] << 8) | b[1]) * 0.1f;
  float t = (((b[2] & 0x7F) << 8) | b[3]) * 0.1f;
  if (b[2] & 0x80) t = -t;
  if (h < 0 || h > 100 || t < -40 || t > 80) return DhtResult::OutOfRange;   // DHT22 datasheet limits
  tempC = t;
  humPct = h;
  return DhtResult::Ok;
}

/** How long until the next radio pause for an ADC2 gas sample: sooner while the gas is near the threshold or a warning
 *  is on, so an incident is confirmed (and ended) quickly. */
inline uint32_t gasPauseInterval(int lastRaw, bool haveRaw, bool warning, int clearBelow, uint32_t normalMs, uint32_t fastMs) {
  if (warning || (haveRaw && lastRaw >= clearBelow)) return fastMs;
  return normalMs;
}

// ---------------------------------------------------------------------------------------------------------------------
// PIR motion filter, fed the raw pin level with the current time (ms, wrap-safe).
//   warm-up : for warmupMs after start the output is ignored (a PIR's output is unreliable while it settles).
//   debounce: a level change only counts once it has been stable for debounceMs (spikes and glitches are ignored).
//   hold    : motion stays true for holdMs after the debounced level returns LOW, so it does not flicker.
// A genuine HIGH that lasts longer than debounceMs always produces motion, so real events are never hidden.
// ---------------------------------------------------------------------------------------------------------------------
class MotionFilter {
 public:
  MotionFilter(uint32_t debounceMs, uint32_t holdMs, uint32_t warmupMs)
      : debounce_(debounceMs), hold_(holdMs), warmup_(warmupMs) {}

  void start(uint32_t nowMs) { start_ = nowMs; started_ = true; }

  bool update(bool rawHigh, uint32_t nowMs) {
    if (!started_) start(nowMs);
    if (rawHigh != candidate_) {
      candidate_ = rawHigh;
      candidateSince_ = nowMs;
    }
    if (candidate_ != stable_ && nowMs - candidateSince_ >= debounce_) {
      stable_ = candidate_;
      if (stable_) highSince_ = nowMs;
      else lowSince_ = nowMs;
    }
    if (warmingUp(nowMs)) {
      motion_ = false;
    } else if (stable_) {
      motion_ = true;
    } else {
      motion_ = motion_ && (nowMs - lowSince_ < hold_);
    }
    return motion_;
  }

  bool active() const { return motion_; }
  bool warmingUp(uint32_t nowMs) const { return started_ && nowMs - start_ < warmup_; }
  /** How long the debounced output has been HIGH without a break (0 when LOW). */
  uint32_t highFor(uint32_t nowMs) const { return stable_ ? nowMs - highSince_ : 0; }
  /** Debounced pin level (diagnostics). */
  bool stable() const { return stable_; }
  /** The level waiting to become stable (diagnostics): differs from stable() while a change is being debounced. */
  bool candidate() const { return candidate_; }

 private:
  uint32_t debounce_, hold_, warmup_;
  uint32_t start_ = 0, candidateSince_ = 0, highSince_ = 0, lowSince_ = 0;
  bool started_ = false, candidate_ = false, stable_ = false, motion_ = false;
};

// =====================================================================================================================
// Remote output control (Chunk 5)
// =====================================================================================================================

// ---------------------------------------------------------------------------------------------------------------------
// LED modes.
//   Auto = the local logic decides (PIR LED follows motion; gas LED shows the gas warning).
//   On   = forced on.   Off = forced off (PIR LED only).
// SAFETY: the gas warning always wins. gasLedOutput() is true whenever a gas warning is active, whatever the mode,
// so no remote command, bug or override can hide a gas alarm.
// ---------------------------------------------------------------------------------------------------------------------
enum class LedMode { Auto, On, Off };

inline const char* ledModeName(LedMode m) {
  switch (m) {
    case LedMode::On: return "on";
    case LedMode::Off: return "off";
    default: return "auto";
  }
}

inline bool parseLedMode(const char* s, LedMode& out) {
  if (!s) return false;
  if (strcmp(s, "auto") == 0) { out = LedMode::Auto; return true; }
  if (strcmp(s, "on") == 0) { out = LedMode::On; return true; }
  if (strcmp(s, "off") == 0) { out = LedMode::Off; return true; }
  return false;
}

inline bool pirLedOutput(LedMode mode, bool motionNow) {
  if (mode == LedMode::On) return true;
  if (mode == LedMode::Off) return false;
  return motionNow;
}

inline bool gasLedOutput(LedMode mode, bool gasWarning) {
  if (gasWarning) return true;          // local safety logic is never overridden
  return mode == LedMode::On;           // "off" (and auto) show nothing extra when there is no warning
}

// ---------------------------------------------------------------------------------------------------------------------
// Command validation. Mirrors the backend allow-list exactly (CommandActions.java):
//   set_pir_led : auto | on | off
//   set_gas_led : auto | on        (never off)
// A command is only applied if its id is a UUID, the action and value are allowed, the clock is set and it has not
// expired. Anything else is answered with a rejection, never silently executed.
// ---------------------------------------------------------------------------------------------------------------------
enum class CmdAction { None, SetPirLed, SetGasLed };

enum class CmdVerdict { Ok, BadId, BadAction, BadValue, ForbiddenValue, BadExpiry, ClockNotSet, Expired, ExpiryTooFar };

struct CommandFields {
  const char* id = nullptr;      // null if absent or not a string
  const char* action = nullptr;
  const char* value = nullptr;
  bool hasExpiry = false;        // expiresAt present AND an integer
  int64_t expiresAt = 0;         // epoch seconds
};

struct CommandCheck {
  CmdVerdict verdict = CmdVerdict::BadId;
  CmdAction action = CmdAction::None;
  LedMode mode = LedMode::Auto;
};

/** 8-4-4-4-12 hex digits with dashes (36 chars). */
inline bool isUuid(const char* s) {
  if (!s || strlen(s) != 36) return false;
  for (int i = 0; i < 36; i++) {
    char c = s[i];
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (c != '-') return false;
    } else if (!isxdigit((unsigned char)c)) {
      return false;
    }
  }
  return true;
}

inline CommandCheck validateCommand(const CommandFields& f, bool clockSet, int64_t nowEpoch, int64_t maxLifetimeSeconds) {
  CommandCheck r;
  if (!isUuid(f.id)) { r.verdict = CmdVerdict::BadId; return r; }

  if (f.action && strcmp(f.action, "set_pir_led") == 0) r.action = CmdAction::SetPirLed;
  else if (f.action && strcmp(f.action, "set_gas_led") == 0) r.action = CmdAction::SetGasLed;
  else { r.verdict = CmdVerdict::BadAction; return r; }

  if (!parseLedMode(f.value, r.mode)) { r.verdict = CmdVerdict::BadValue; return r; }
  if (r.action == CmdAction::SetGasLed && r.mode == LedMode::Off) { r.verdict = CmdVerdict::ForbiddenValue; return r; }

  if (!f.hasExpiry) { r.verdict = CmdVerdict::BadExpiry; return r; }
  if (!clockSet) { r.verdict = CmdVerdict::ClockNotSet; return r; }   // cannot judge expiry, so do not act
  if (nowEpoch > f.expiresAt) { r.verdict = CmdVerdict::Expired; return r; }
  if (f.expiresAt - nowEpoch > maxLifetimeSeconds) { r.verdict = CmdVerdict::ExpiryTooFar; return r; }

  r.verdict = CmdVerdict::Ok;
  return r;
}

/** Ack status the backend understands: applied | rejected | expired. */
inline const char* verdictStatus(CmdVerdict v) {
  if (v == CmdVerdict::Ok) return "applied";
  if (v == CmdVerdict::Expired) return "expired";
  return "rejected";
}

/** Short fixed text (no quotes or control characters, so it is safe to embed in JSON as is). */
inline const char* verdictDetail(CmdVerdict v) {
  switch (v) {
    case CmdVerdict::Ok: return "";
    case CmdVerdict::BadId: return "invalid command id";
    case CmdVerdict::BadAction: return "unsupported action";
    case CmdVerdict::BadValue: return "unsupported value";
    case CmdVerdict::ForbiddenValue: return "the gas warning LED cannot be turned off";
    case CmdVerdict::BadExpiry: return "missing or invalid expiry";
    case CmdVerdict::ClockNotSet: return "device clock not set yet";
    case CmdVerdict::Expired: return "command expired before it arrived";
    case CmdVerdict::ExpiryTooFar: return "expiry too far in the future";
  }
  return "";
}

// ---------------------------------------------------------------------------------------------------------------------
// Duplicate protection. The broker may deliver a QoS 1 command more than once. A repeated id must never be applied
// twice; instead the original answer is sent again.
// ---------------------------------------------------------------------------------------------------------------------
class RecentCommands {
 public:
  static constexpr int CAPACITY = 16;

  struct Entry {
    char id[37] = {0};
    const char* status = "";   // points at string literals only
    const char* detail = "";
  };

  /** The remembered answer for this id, or nullptr if it has not been seen. */
  const Entry* find(const char* id) const {
    for (int i = 0; i < CAPACITY; i++) {
      if (entries_[i].id[0] && strcmp(entries_[i].id, id) == 0) return &entries_[i];
    }
    return nullptr;
  }

  void remember(const char* id, const char* status, const char* detail) {
    Entry& e = entries_[next_];
    next_ = (next_ + 1) % CAPACITY;
    strncpy(e.id, id, 36);
    e.id[36] = '\0';
    e.status = status;
    e.detail = detail;
  }

 private:
  Entry entries_[CAPACITY];
  int next_ = 0;
};

// ---------------------------------------------------------------------------------------------------------------------
// Replies, in the exact shapes the backend validates.
//   ack   : {"id":"<uuid>","status":"applied|rejected|expired","detail":"..."}      (detail omitted when empty)
//   state : {"pirLed":{"mode":"auto","on":false},"gasLed":{"mode":"auto","on":false},"gasWarning":false,
//            "lastCommandId":"<uuid>"}                                             (lastCommandId omitted if none)
// ---------------------------------------------------------------------------------------------------------------------
inline int buildAckJson(char* out, size_t cap, const char* id, const char* status, const char* detail) {
  int n = (detail && *detail)
              ? snprintf(out, cap, "{\"id\":\"%s\",\"status\":\"%s\",\"detail\":\"%s\"}", id, status, detail)
              : snprintf(out, cap, "{\"id\":\"%s\",\"status\":\"%s\"}", id, status);
  return (n < 0 || (size_t)n >= cap) ? -1 : n;
}

inline int buildStateJson(char* out, size_t cap, LedMode pirMode, bool pirOn, LedMode gasMode, bool gasOn, bool gasWarning,
                          const char* lastCommandId) {
  int n = snprintf(out, cap,
                   "{\"pirLed\":{\"mode\":\"%s\",\"on\":%s},\"gasLed\":{\"mode\":\"%s\",\"on\":%s},\"gasWarning\":%s",
                   ledModeName(pirMode), pirOn ? "true" : "false", ledModeName(gasMode), gasOn ? "true" : "false",
                   gasWarning ? "true" : "false");
  if (n < 0 || (size_t)n >= cap) return -1;
  size_t len = (size_t)n;
  if (lastCommandId && *lastCommandId) {
    int m = snprintf(out + len, cap - len, ",\"lastCommandId\":\"%s\"", lastCommandId);
    if (m < 0 || (size_t)m >= cap - len) return -1;
    len += (size_t)m;
  }
  if (len + 2 > cap) return -1;
  out[len++] = '}';
  out[len] = '\0';
  return (int)len;
}
