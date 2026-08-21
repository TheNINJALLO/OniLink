export type GlobalRole = "viewer" | "operator" | "admin" | "owner";
export type Role = "tenant" | GlobalRole;

export interface Principal {
  username: string;
  role: Role;
  tenantId: string;
}

export interface SessionPayload extends Principal {
  token: string;
  expiresAt: number;
}

export interface SetupStatus {
  setupRequired: boolean;
  minimumPasswordLength: number;
  setupFile: string;
}

export interface ListenerAddress {
  host: string;
  port: number;
}

export interface RuntimeState {
  name: string;
  version: string;
  timestamp: string;
  startedAt: string;
  uptimeMillis: number;
  players: number;
  maxPlayers: number;
  backends: number;
  allowlistEnabled: boolean;
  allowlistEntries: number;
  listener: ListenerAddress;
  memoryUsedBytes: number;
  memoryCommittedBytes: number;
  memoryMaxBytes: number;
  processors: number;
  threads: number;
  systemLoadAverage: number;
  principal: Principal;
}

export interface Player {
  name: string;
  xuid: string;
  identity: string;
  backend: string;
  switching: boolean;
  switchTarget: string;
  connectedMillis: number;
  joinedWorld: boolean;
  protocol: string;
  address: string;
  packetTraceActive: boolean;
}

export interface PacketProtocol {
  protocol: number;
  minecraftVersion: string;
  packetModels: number;
}

export interface PacketProtocolPair {
  clientProtocol: number;
  clientVersion: string;
  backendProtocol: number;
  backendVersion: string;
}

export type PacketMatchStatus =
  | "native"
  | "automatic_codec_match"
  | "explicit_translation"
  | "review_required"
  | "unknown_packet";

export interface PacketObservation {
  sequence: number;
  timestamp: string;
  direction: "serverbound" | "clientbound";
  directionLabel: string;
  packetName: string;
  sourcePacketId: number;
  targetPacketId: number;
  sourceProtocol: number;
  sourceVersion: string;
  targetProtocol: number;
  targetVersion: string;
  status: PacketMatchStatus;
  action: string;
  player: string;
  xuid: string;
  clientAddress: string;
  backend: string;
  backendAddress: string;
  suggestion: string;
  decodedPayload?: string;
  translatedPayload?: string;
  wireBytesBase64?: string;
  wireBytesLength: number;
  wireHeaderLength: number;
  tokenRedacted: boolean;
  redactionReason: string;
}

export interface PacketMatch {
  direction: "serverbound" | "clientbound";
  packetName: string;
  sourcePacketId: number;
  targetPacketId: number;
  sourceProtocol: number;
  targetProtocol: number;
  status: PacketMatchStatus;
  action: string;
  suggestion: string;
  count: number;
  lastSeen: string;
}

export interface PacketCatalogEntry {
  direction: "serverbound" | "clientbound";
  packetName: string;
  sourcePacketId: number;
  targetPacketId: number;
  status: "native" | "automatic_codec_match" | "review_required";
  candidate: string;
  observedCount: number;
}

export interface PacketMonitorSummary {
  observedPackets: number;
  storedRecords: number;
  uniqueMatches: number;
  nativeMatches: number;
  automaticMatches: number;
  explicitTranslations: number;
  reviewRequired: number;
  droppedPackets: number;
  sampledOut: number;
  evictedRecords: number;
  capacity: number;
  movementSampleRate: number;
  retainedCaptureBytes: number;
  captureBudgetBytes: number;
  tokenRedactions: number;
}

export interface PacketMonitorSnapshot {
  enabled: boolean;
  privacy: string;
  summary: PacketMonitorSummary;
  protocols: PacketProtocol[];
  selectedPair: PacketProtocolPair;
  routeAvailable: boolean;
  records: PacketObservation[];
  matches: PacketMatch[];
  catalog: PacketCatalogEntry[];
  catalogCount: number;
}

export type HealthState = "online" | "degraded" | "offline" | "checking";

export interface BackendHealth {
  status: HealthState;
  latencyMillis: number;
  checkedAt?: string;
  message?: string;
}

export interface Backend {
  name: string;
  host: string;
  port: number;
  protocol: string;
  players: number;
  default: boolean;
  hub: boolean;
  forwarding: boolean;
  health: BackendHealth;
}

export interface AllowlistEntry {
  xuid: string;
  name: string;
}

export interface Allowlist {
  enabled: boolean;
  count: number;
  file?: string;
  disconnectOnRemoval?: boolean;
  entries: AllowlistEntry[];
}

export interface Configuration {
  path: string;
  content: string;
  revision: string;
  backupAvailable: boolean;
  redactedPlaceholder: string;
  restartRequired?: boolean;
  message?: string;
}

export interface BackendSetup extends Configuration {
  backendName: string;
  secret: string;
  secretFileName: string;
  onilinkSecretFile: string;
  onilinkProperties: string;
  onibridgeToml: string;
  controlEnabled?: boolean;
  controlSecret?: string;
  controlSecretFileName?: string;
  onilinkControlSecretFile?: string;
  controlEndpoint?: string;
  backendEndpoint: string;
  trustedProxyCidr: string;
  setupBundleFileName: string;
  setupBundleBase64: string;
  restartRequired: boolean;
  message: string;
}

export interface ActionResult {
  success: boolean;
  message: string;
}

export interface OniControlBridgeStatus {
  enabled: boolean;
  connected: boolean;
  tls: boolean;
  backend: string;
  bridgeId: string;
  capabilityRevision: number;
  latencyMillis: number;
  queueSize: number;
  supportedActionCount: number;
  lastError: string;
  updatedAt: string;
}

export interface OniControlActionCapability {
  action: string;
  executionPlane: "CLIENT_ONLY" | "BACKEND_AUTHORITATIVE" | "VIRTUALIZED";
  minimumRole: string;
  destructive: boolean;
  supported: boolean;
  reason: string;
  payloadVersion: number;
}

export interface OniControlStatus {
  started: boolean;
  available: boolean;
  controlEnabled: boolean;
  packetRulesEnabled: boolean;
  virtualizationEnabled: boolean;
  protocolLabEnabled: boolean;
  tenantId: string;
  proxyId: string;
  ruleCount: number;
  historyCount: number;
  bridges: OniControlBridgeStatus[];
  capabilities: Array<Record<string, unknown>>;
  packetRuleMetrics: Record<string, number>;
  packetFactoryMetrics: Record<string, number>;
  virtualInventorySessions: Array<Record<string, unknown>>;
  privateEntities: Array<Record<string, unknown>>;
  fakeBlocks: Array<Record<string, unknown>>;
}

export interface ProtocolLabStatus {
  enabled: boolean;
  backendBoundEnabled: boolean;
  sessionActive: boolean;
  sessionExpiresAt: string;
  maximumPacketsPerMinute: number;
  models: Array<{
    model: string;
    direction: "CLIENTBOUND";
    fields: Record<string, string>;
  }>;
}

export interface ProtocolLabResult {
  valid: boolean;
  sent: boolean;
  model: string;
  direction: string;
  packetCount: number;
  encodedBytes: number;
  clientProtocol: number;
  sessionExpiresAt: string;
}

export interface OniControlTarget {
  xuid: string;
  connectionId: string;
  displayName: string;
  tenantId: string;
  proxyId: string;
  backend: string;
  clientProtocol: number;
  backendProtocol: number;
  joinedWorld: boolean;
  transferInProgress: boolean;
}

export interface OniControlPreview {
  confirmationToken: string;
  revision: string;
  expiresAt: string;
  action: string;
  executionPlane: OniControlActionCapability["executionPlane"];
  destructive: boolean;
  target: OniControlTarget;
  payloadSummary: { version: number; fields: string[] };
  reason: string;
}

export interface OniControlResult {
  requestId: string;
  status: string;
  success: boolean;
  reason: string;
  result: Record<string, unknown>;
  startedAt: string;
  completedAt: string;
  durationMillis: number;
  auditReference: string;
}

export interface OniControlPlanPreview {
  valid: boolean;
  planId: string;
  revision: number;
  failurePolicy: string;
  requiredRole: string;
  confirmationRequired: boolean;
  stepCount: number;
  steps: Array<Record<string, unknown>>;
  reason: string;
  expectedResult: string;
  confidence: number;
  confirmationToken: string;
  expiresAt: string;
}

export interface OniControlPlanResult {
  planId: string;
  revision: number;
  status: string;
  success: boolean;
  partial: boolean;
  stopped: boolean;
  failurePolicy: string;
  results: Array<Record<string, unknown>>;
  compensation?: string;
}

export interface OniControlHistoryRecord {
  requestId: string;
  actor: string;
  role: string;
  tenantId: string;
  proxyId: string;
  targetXuid: string;
  displayLabel: string;
  backend: string;
  action: string;
  executionPlane: OniControlActionCapability["executionPlane"];
  status: string;
  timestamp: string;
  durationMillis: number;
  payloadSummary: string;
  resultSummary: string;
  failureReason: string;
  confirmed: boolean;
}

export interface DashboardUser extends Principal {
  enabled: boolean;
  totpEnabled: boolean;
}

export interface Tenant {
  id: string;
  label: string;
  suspended: boolean;
  users: DashboardUser[];
  createdAt: string;
  updatedAt: string;
}

export interface TenantProxy {
  id: string;
  tenantId: string;
  label: string;
  port: number;
  publicAddress: string;
  backendAddress: string;
  primaryBackend: string;
  trustedProxyCidr: string;
  bdsProfile: string;
  maxPlayers: number;
  motd: string;
  enabled: boolean;
  running: boolean;
  status: string;
  lastError: string;
  handoffAvailable: boolean;
}

export interface TenancyOverview {
  mode: "single-container";
  providerPort: number;
  tenants: Tenant[];
  proxies: TenantProxy[];
  tenantScope: string;
}

export interface TenantProxyDashboard {
  proxy: TenantProxy;
  state: Partial<RuntimeState>;
  players: Player[];
  backends: Backend[];
  primaryBackend: string;
  primaryBackendAddress: string;
  configuredBackends: Array<{ name: string; address: string }>;
  allowlist: Allowlist;
  configurationRevision: string;
}

export interface AuditEvent {
  timestamp: string;
  actor: string;
  role: string;
  remoteAddress: string;
  action: string;
  result: string;
  details: unknown;
  raw: string;
}
