export interface UserProfile {
  id: string
  username: string
  email: string
  createdAt: string
}

export interface AuthResponse {
  token: string
  tokenType: string
  expiresInSeconds: number
  user: UserProfile
}

/** Error from the backend, carrying the HTTP status and per-field messages when present. */
export class ApiError extends Error {
  status: number
  fieldErrors: Record<string, string>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

const TOKEN_KEY = 'smarthome.token'

// localStorage keeps the user signed in across reloads. Trade-off: readable by any script on the page (XSS).
export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

type UnauthorizedListener = () => void
let onUnauthorized: UnauthorizedListener = () => {}
export const setUnauthorizedListener = (fn: UnauthorizedListener) => {
  onUnauthorized = fn
}

async function request<T>(path: string, init: RequestInit = {}, authenticated = false): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  const token = tokenStore.get()
  if (authenticated && token) headers.set('Authorization', `Bearer ${token}`)

  let res: Response
  try {
    res = await fetch(path, { ...init, headers })
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Is the backend running?')
  }

  if (res.status === 204) return undefined as T
  if (res.ok) return (await res.json()) as T

  let body: { message?: string; fieldErrors?: Record<string, string> } = {}
  try {
    body = await res.json()
  } catch {
    // non-JSON error body
  }
  // An expired/invalid token on a protected call means the session is over.
  if (res.status === 401 && authenticated) onUnauthorized()
  throw new ApiError(res.status, body.message ?? `Request failed (HTTP ${res.status})`, body.fieldErrors)
}

/** One stored reading. null = that sensor sent nothing (missing), never a made-up 0. */
export interface Telemetry {
  receivedAt: string
  deviceTimestamp: string | null
  temperatureC: number | null
  humidityPct: number | null
  motion: boolean | null
  /** Raw MQ-2 ADC value (0-4095). Not ppm: that needs calibration. */
  gasRaw: number | null
  ageSeconds: number
  stale: boolean
}

export type Connection = 'NEVER_CONNECTED' | 'ONLINE' | 'OFFLINE'

export type CommandStatus = 'PENDING' | 'SENT' | 'ACKNOWLEDGED' | 'FAILED' | 'EXPIRED' | 'UNKNOWN'

export interface Command {
  id: string
  action: string
  value: string
  status: CommandStatus
  createdAt: string
  expiresAt: string
  sentAt: string | null
  completedAt: string | null
  /** Short reason, e.g. why the device rejected it. */
  detail: string | null
  /** Set when an automation (not a person) sent it, e.g. "automation:Night light". */
  origin: string | null
}

/** A controllable output as advertised by the server (the UI renders its buttons from this). */
export interface OutputSpec {
  action: string
  label: string
  values: string[]
}

export interface LedState {
  mode: string
  on: boolean
}

/** What the device itself last reported (not what was requested). */
export interface ReportedState {
  pirLed: LedState
  gasLed: LedState
  gasWarning: boolean
  lastCommandId: string | null
  reportedAt: string
  ageSeconds: number
  stale: boolean
}

export type SensorId = 'temperature' | 'humidity' | 'motion' | 'gas'

export interface RuleCondition {
  sensor: SensorId
  operator: string
  /** A number, or true/false for motion. */
  value: number | boolean
}

export interface RuleTrigger {
  type: 'TELEMETRY' | 'SCHEDULE'
  time: string | null
  days: string[] | null
  timeZone: string | null
}

export interface Rule {
  id: string
  name: string
  deviceId: string
  targetDeviceId: string
  enabled: boolean
  trigger: RuleTrigger
  logic: 'AND' | 'OR'
  conditions: RuleCondition[]
  action: { action: string; value: string }
  priority: number
  cooldownSeconds: number
  lastExecutedAt: string | null
  createdAt: string
  updatedAt: string
}

/** What the server accepts when creating or replacing a rule. */
export interface RuleRequest {
  name: string
  deviceId: string
  targetDeviceId?: string
  enabled: boolean
  trigger: { type: 'TELEMETRY' } | { type: 'SCHEDULE'; time: string; days: string[]; timeZone: string }
  logic: 'AND' | 'OR'
  conditions: RuleCondition[]
  action: { action: string; value: string }
  priority: number
  cooldownSeconds: number
}

export type ExecutionOutcome =
  | 'EXECUTED'
  | 'FAILED'
  | 'SKIPPED_COOLDOWN'
  | 'SKIPPED_OFFLINE'
  | 'SKIPPED_NO_DATA'
  | 'SKIPPED_STALE_DATA'
  | 'SKIPPED_CONFLICT'
  | 'SKIPPED_ALREADY_SET'
  | 'SKIPPED_RATE_LIMITED'
  | 'SKIPPED_BUSY'
  | 'SKIPPED_NOT_OWNED'
  | 'SKIPPED_UNSAFE'
  | 'SKIPPED_CONDITIONS_FALSE'
  | 'SKIPPED_MISSED'

export interface Execution {
  id: string
  ruleId: string
  ruleName: string
  deviceId: string
  triggerType: 'TELEMETRY' | 'SCHEDULE'
  outcome: ExecutionOutcome
  reason: string | null
  action: string
  value: string
  /** Sensor values seen when the rule was evaluated (null = no reading). */
  triggerValues: Record<string, unknown> | null
  evaluation: { sensor: string; operator: string; expected: unknown; actual: unknown; result: string }[] | null
  commandId: string | null
  commandStatus: CommandStatus | null
  createdAt: string
  lastAt: string
  repeatCount: number
}

// ---- ML insights --------------------------------------------------------------------------------------------------

export type OccupancyState = 'NOT_TRAINED' | 'INSUFFICIENT_DATA' | 'READY' | 'LOW_QUALITY'

export interface MlCounts {
  motionReadings: number
  usableSlots: number
  slotsWithMotion: number
  slotsWithoutMotion: number
  sparseSlotsIgnored: number
  distinctDays: number
  dataFrom: string | null
  dataTo: string | null
}

export interface MlRequirements {
  slotMinutes: number
  minReadingsPerSlot: number
  minUsableSlots: number
  minDistinctDays: number
  minSlotsPerClass: number
}

/** Evaluation on the latest slots the evaluation model had not seen. null = not defined, never invented. */
export interface MlMetrics {
  trainSlots: number
  testSlots: number
  testSlotsWithMotion: number
  accuracy: number
  precision: number | null
  recall: number | null
  f1: number | null
  auc: number | null
  logLoss: number
  brier: number
  baselineAccuracy: number
  baselineLogLoss: number
  evaluation: string
}

export interface OccupancyStatus {
  state: OccupancyState
  predictionsAvailable: boolean
  message: string
  trainedAt: string | null
  timeZone: string | null
  counts: MlCounts | null
  requirements: MlRequirements
  metrics: MlMetrics | null
  data: { motionReadings: number; firstAt: string | null; lastAt: string | null }
  method: string
}

export type AnomalyKind = 'STATISTICAL_OUTLIER' | 'OUT_OF_RANGE' | 'STUCK_VALUE' | 'MISSING_DATA' | 'STALE_DATA'
export type AnomalySeverity = 'INFO' | 'WARNING' | 'CRITICAL'
export type AnomalyStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'DISMISSED' | 'AUTO_RESOLVED'

export interface Anomaly {
  id: string
  kind: AnomalyKind
  scope: string
  severity: AnomalySeverity
  status: AnomalyStatus
  explanation: string
  details: Record<string, unknown> | null
  detectedAt: string
  lastSeenAt: string
  occurrences: number
  readingAt: string | null
  resolvedAt: string | null
  resolutionNote: string | null
}

export interface MlStatus {
  occupancy: OccupancyStatus
  anomalies: { active: number; critical: number; warning: number; info: number; lastScanAt: string | null }
  notice: string
}

export interface Prediction {
  at: string
  localTime: string
  dayOfWeek: string
  hour: number
  probability: number
  predictedOccupied: boolean
  confidence: number
  support: number
  reliable: boolean
  observedRate: number | null
  quality: string
}

export interface Forecast {
  timeZone: string
  quality: string
  points: Prediction[]
}

export interface Device {
  id: string
  deviceId: string
  name: string
  status: 'ACTIVE' | 'DISOWNED'
  registeredAt: string
  connection: Connection
  lastSeenAt: string | null
  lastSeenAgeSeconds: number | null
  /** After this many seconds of silence, a device counts as offline and its readings as stale. */
  staleAfterSeconds: number
  latestTelemetry: Telemetry | null
  outputs: OutputSpec[]
  reportedState: ReportedState | null
  /** Newest first, at most 5. */
  recentCommands: Command[]
}

export interface PairingCode {
  code: string
  name: string
  expiresAt: string
  expiresInSeconds: number
}

export type AnswerStatus = 'ANSWERED' | 'NO_CONTEXT' | 'NOT_CONFIGURED' | 'LLM_UNAVAILABLE' | 'RETRIEVAL_UNAVAILABLE'

/** A reference shown under an answer. `cited` = the answer points at it. */
export interface ChatSource {
  ref: string
  type: string
  title: string
  snippet: string
  score: number
  asOf: string
  deviceId: string | null
  cited: boolean
}

/** A rule the assistant proposed. It only exists as a proposal until the owner approves it. */
export interface ChatSuggestion {
  id: string
  summary: string
  rationale: string | null
  description: string
  rule: unknown
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED'
  createdAt: string
  expiresAt: string
  createdRuleId: string | null
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  status: AnswerStatus | null
  sources: ChatSource[]
  warnings: string[]
  suggestions: ChatSuggestion[]
  createdAt: string
}

export interface ChatReply {
  conversationId: string
  conversationTitle: string
  userMessage: ChatMessage
  assistantMessage: ChatMessage
}

export interface Conversation {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface KnowledgeDoc {
  id: string
  title: string
  addedAt: string
  chunks: number
}

export interface ModelInfo {
  configured: boolean
  provider: string
  model: string
  note: string
}

export interface AssistantStatus {
  llm: ModelInfo
  embedding: ModelInfo
  index: { yourChunks: number; sharedChunks: number; documents: number }
  limits: { maxMessageChars: number; maxDocumentChars: number; maxDocuments: number; topK: number }
  notice: string
}

// ---- security modes ----
export type SecurityMode = 'HOME' | 'AWAY' | 'NIGHT'
export interface ModeConfig {
  motionAlerts: boolean
  cooldownSeconds: number
  lightOnMotion: boolean
}
export interface SecuritySettings {
  mode: SecurityMode
  modeSince: string
  away: ModeConfig
  night: ModeConfig
  unacknowledgedAlerts: number
  pirLedForcedBySecurity: boolean
  notice: string
}
export type SecurityEventKind = 'MODE_CHANGED' | 'SETTINGS_CHANGED' | 'MOTION_ALERT' | 'DEVICE_OFFLINE' | 'ACTION_TAKEN' | 'ACTION_FAILED'
export interface SecurityEvent {
  id: string
  deviceId: string | null
  kind: SecurityEventKind
  severity: 'INFO' | 'WARNING' | 'CRITICAL'
  mode: SecurityMode
  message: string
  createdAt: string
  acknowledgedAt: string | null
}

// ---- gas/smoke email alerts ----
export type EmailDelivery = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED'
export interface GasAlertConfig {
  enabled: boolean
  mailConfigured: boolean
  threshold: number
  recoveryThreshold: number
  confirmReadings: number
  /** Masked, e.g. "y***@gmail.com"; null when the account has no email. */
  recipient: string | null
}
export interface GasAlert {
  id: string
  deviceId: string
  deviceName: string
  state: 'ACTIVE' | 'RECOVERED'
  threshold: number
  triggerGasRaw: number
  peakGasRaw: number
  lastGasRaw: number
  startedAt: string
  recoveredAt: string | null
  alertEmailStatus: EmailDelivery
  alertEmailSentAt: string | null
  alertEmailError: string | null
  recoveryEmailStatus: EmailDelivery | null
  recoveryEmailSentAt: string | null
  recoveryEmailError: string | null
}

// ---- digital twin ----
export type SyncState = 'NO_DESIRED' | 'IN_SYNC' | 'PENDING' | 'DRIFT' | 'UNREPORTED' | 'UNKNOWN'
export interface OutputTwin {
  action: string
  label: string
  desired: string | null
  desiredBy: string | null
  desiredAt: string | null
  requested: string | null
  reportedMode: string | null
  reportedLit: boolean | null
  reportedAt: string | null
  reportedStale: boolean
  sync: SyncState
  note: string
}
export interface Twin {
  deviceId: string
  name: string
  connection: Connection
  lastSeenAt: string | null
  lastSeenAgeSeconds: number | null
  lastReadingAt: string | null
  lastReadingAgeSeconds: number | null
  readingStale: boolean
  gasWarningActive: boolean | null
  outputs: OutputTwin[]
  pendingCommands: { id: string; action: string; value: string; status: CommandStatus; createdAt: string; expiresAt: string; origin: string | null }[]
  problemCommands24h: number
  summary: string
  computedAt: string
}

// ---- recommendations ----
export interface Recommendation {
  id: string
  deviceId: string | null
  actionType: 'CREATE_RULE' | 'DISABLE_RULE'
  title: string
  rationale: string
  evidence: Record<string, unknown>
  description: string
  rule: unknown
  ruleId: string | null
  status: 'PENDING' | 'APPLIED' | 'DISMISSED' | 'STALE'
  createdAt: string
  decidedAt: string | null
  createdRuleId: string | null
}
export interface RecommendationList {
  recommendations: Recommendation[]
  notes: string[]
  generatedAt: string
}

// ---- system status ----
export type Level = 'OK' | 'DEGRADED' | 'DOWN'
export interface SystemStatus {
  overall: Level
  checkedAt: string
  issues: { level: Level; area: string; message: string }[]
  database: { level: Level; detail: string }
  mqtt: { level: Level; detail: string }
  devices: { deviceId: string; name: string; connection: Connection; lastSeenAgeSeconds: number | null }[]
  commands: {
    windowHours: number
    total: number
    byStatus: Record<string, number>
    failureRate: number | null
    recentProblems: { deviceId: string; action: string; value: string; status: string; detail: string | null; at: string }[]
  }
  sensors: { deviceId: string; name: string; connection: Connection; sensors: Record<string, { state: 'FRESH' | 'STALE' | 'NEVER'; ageSeconds: number | null }> }[]
  ml: { deviceId: string; name: string; occupancyState: string; predictionsAvailable: boolean; activeAnomalies: number }[]
  assistant: { level: Level; modelConfigured: boolean; provider: string; model: string; embedding: string; indexReadable: boolean; detail: string }
}

export const api = {
  securitySettings: () => request<SecuritySettings>('/api/security/settings', {}, true),
  setSecurityMode: (mode: SecurityMode) => request<SecuritySettings>('/api/security/mode', { method: 'PUT', body: JSON.stringify({ mode }) }, true),
  configureSecurity: (cfg: { away?: ModeConfig; night?: ModeConfig }) =>
    request<SecuritySettings>('/api/security/config', { method: 'PUT', body: JSON.stringify(cfg) }, true),
  securityEvents: (limit = 30) => request<SecurityEvent[]>(`/api/security/events?limit=${limit}`, {}, true),
  acknowledgeEvent: (id: string) => request<SecurityEvent>(`/api/security/events/${id}/acknowledge`, { method: 'POST' }, true),
  gasAlertConfig: () => request<GasAlertConfig>('/api/alerts/gas/config', {}, true),
  gasAlerts: (limit = 20) => request<GasAlert[]>(`/api/alerts/gas?limit=${limit}`, {}, true),
  acknowledgeAllEvents: () => request<{ acknowledged: number }>('/api/security/events/acknowledge-all', { method: 'POST' }, true),
  twins: () => request<Twin[]>('/api/twins', {}, true),
  recommendations: () => request<RecommendationList>('/api/recommendations', {}, true),
  refreshRecommendations: () => request<RecommendationList>('/api/recommendations/refresh', { method: 'POST' }, true),
  approveRecommendation: (id: string) => request<Recommendation>(`/api/recommendations/${id}/approve`, { method: 'POST' }, true),
  dismissRecommendation: (id: string) => request<Recommendation>(`/api/recommendations/${id}/dismiss`, { method: 'POST' }, true),
  systemStatus: () => request<SystemStatus>('/api/system/status', {}, true),
  assistantStatus: () => request<AssistantStatus>('/api/assistant/status', {}, true),
  chat: (message: string, conversationId?: string) =>
    request<ChatReply>('/api/assistant/chat', { method: 'POST', body: JSON.stringify({ message, conversationId }) }, true),
  conversations: () => request<Conversation[]>('/api/assistant/conversations', {}, true),
  conversationMessages: (id: string) => request<ChatMessage[]>(`/api/assistant/conversations/${id}/messages`, {}, true),
  deleteConversation: (id: string) => request<void>(`/api/assistant/conversations/${id}`, { method: 'DELETE' }, true),
  knowledgeDocs: () => request<KnowledgeDoc[]>('/api/assistant/documents', {}, true),
  addKnowledgeDoc: (title: string, text: string) =>
    request<KnowledgeDoc>('/api/assistant/documents', { method: 'POST', body: JSON.stringify({ title, text }) }, true),
  deleteKnowledgeDoc: (id: string) => request<void>(`/api/assistant/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }, true),
  approveSuggestion: (id: string) => request<Rule>(`/api/assistant/suggestions/${id}/approve`, { method: 'POST' }, true),
  rejectSuggestion: (id: string) => request<ChatSuggestion>(`/api/assistant/suggestions/${id}/reject`, { method: 'POST' }, true),
  register: (username: string, email: string, password: string) =>
    request<AuthResponse>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, email, password }),
    }),
  login: (username: string, password: string) =>
    request<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  me: () => request<UserProfile>('/api/auth/me', {}, true),
  listDevices: () => request<Device[]>('/api/devices', {}, true),
  /** Stored readings of one device, newest first (the server caps the limit at 200). */
  telemetryHistory: (deviceId: string, limit = 60) => request<Telemetry[]>(`/api/devices/${deviceId}/telemetry?limit=${limit}`, {}, true),
  createPairingCode: (name: string) =>
    request<PairingCode>('/api/devices/pairing-codes', { method: 'POST', body: JSON.stringify({ name }) }, true),
  renameDevice: (id: string, name: string) =>
    request<Device>(`/api/devices/${id}`, { method: 'PATCH', body: JSON.stringify({ name }) }, true),
  sendCommand: (deviceId: string, action: string, value: string) =>
    request<Command>(`/api/devices/${deviceId}/commands`, { method: 'POST', body: JSON.stringify({ action, value }) }, true),
  getCommand: (deviceId: string, commandId: string) => request<Command>(`/api/devices/${deviceId}/commands/${commandId}`, {}, true),
  listRules: () => request<Rule[]>('/api/automations', {}, true),
  createRule: (r: RuleRequest) => request<Rule>('/api/automations', { method: 'POST', body: JSON.stringify(r) }, true),
  updateRule: (id: string, r: RuleRequest) =>
    request<Rule>(`/api/automations/${id}`, { method: 'PUT', body: JSON.stringify(r) }, true),
  setRuleEnabled: (id: string, enabled: boolean) =>
    request<Rule>(`/api/automations/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) }, true),
  deleteRule: (id: string) => request<void>(`/api/automations/${id}`, { method: 'DELETE' }, true),
  ruleHistory: (id: string, limit = 30) => request<Execution[]>(`/api/automations/${id}/executions?limit=${limit}`, {}, true),
  allHistory: (limit = 50) => request<Execution[]>(`/api/automations/executions?limit=${limit}`, {}, true),
  mlStatus: (deviceId: string) => request<MlStatus>(`/api/devices/${deviceId}/ml/status`, {}, true),
  trainOccupancy: (deviceId: string, timeZone: string) =>
    request<OccupancyStatus>(`/api/devices/${deviceId}/ml/occupancy/train?timeZone=${encodeURIComponent(timeZone)}`, { method: 'POST' }, true),
  predictNow: (deviceId: string) => request<Prediction>(`/api/devices/${deviceId}/ml/occupancy/predict`, {}, true),
  forecast: (deviceId: string, hours = 24) =>
    request<Forecast>(`/api/devices/${deviceId}/ml/occupancy/forecast?hours=${hours}&stepMinutes=60`, {}, true),
  anomalies: (deviceId: string, status: 'active' | 'all') =>
    request<Anomaly[]>(`/api/devices/${deviceId}/ml/anomalies?status=${status}&limit=100`, {}, true),
  resolveAnomaly: (deviceId: string, id: string, status: 'ACKNOWLEDGED' | 'RESOLVED' | 'DISMISSED', note?: string) =>
    request<Anomaly>(`/api/devices/${deviceId}/ml/anomalies/${id}`, { method: 'PATCH', body: JSON.stringify({ status, note: note || undefined }) }, true),
  scanAnomalies: (deviceId: string) =>
    request<{ scannedAt: string; findings: number; activeAnomalies: number }>(`/api/devices/${deviceId}/ml/anomalies/scan`, { method: 'POST' }, true),
  disownDevice: (id: string) => request<void>(`/api/devices/${id}`, { method: 'DELETE' }, true),
  health: () => request<{ status: string; service: string; timestamp: string }>('/api/health'),
}
