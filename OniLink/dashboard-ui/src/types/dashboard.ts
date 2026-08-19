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
  dropSubChunkRequests: boolean;
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
