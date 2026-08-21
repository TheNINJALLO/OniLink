import { download, request } from "./client";
import type {
  ActionResult,
  Allowlist,
  Backend,
  BackendSetup,
  Configuration,
  DashboardUser,
  Player,
  PacketMonitorSnapshot,
  OniControlHistoryRecord,
  OniControlActionCapability,
  OniControlTarget,
  OniControlPlanPreview,
  OniControlPlanResult,
  OniControlPreview,
  OniControlResult,
  OniControlStatus,
  ProtocolLabResult,
  ProtocolLabStatus,
  Principal,
  RuntimeState,
  SessionPayload,
  SetupStatus,
  TenancyOverview,
  TenantProxyDashboard,
} from "../types/dashboard";

export const dashboardApi = {
  platformGet: <T>(path: string, params: Record<string, string> = {}, signal?: AbortSignal) => {
    const query = new URLSearchParams(params);
    return request<T>(`${path}${query.size ? `?${query}` : ""}`, { signal });
  },
  platformMutation: <T>(
    path: string,
    method: "POST" | "PUT" | "DELETE",
    body: Record<string, string | number | boolean>,
  ) => request<T>(path, { method, body }),
  setupStatus: (signal?: AbortSignal) =>
    request<SetupStatus>("/api/setup/status", { authenticated: false, signal }),
  setup: (body: Record<string, string>) =>
    request<SessionPayload>("/api/setup", { method: "POST", body, authenticated: false }),
  login: (body: Record<string, string>) =>
    request<SessionPayload & { totpRequired?: boolean }>("/api/login", {
      method: "POST",
      body,
      authenticated: false,
    }),
  logout: () => request<{ loggedOut: boolean }>("/api/logout", { method: "POST" }),
  whoami: (signal?: AbortSignal) => request<Principal>("/api/whoami", { signal }),
  state: (signal?: AbortSignal) => request<RuntimeState>("/api/state", { signal }),
  players: (signal?: AbortSignal) => request<{ players: Player[] }>("/api/players", { signal }),
  playersForProxy: (proxy: string, signal?: AbortSignal) =>
    request<{ players: Player[] }>(`/api/players?proxy=${encodeURIComponent(proxy)}`, { signal }),
  backends: (signal?: AbortSignal) => request<{ backends: Backend[] }>("/api/backends", { signal }),
  packets: (params: Record<string, string | number>, signal?: AbortSignal) => {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (String(value).trim()) query.set(key, String(value));
    }
    return request<PacketMonitorSnapshot>(`/api/packets?${query}`, { signal });
  },
  oniControlStatus: (proxy: string, signal?: AbortSignal) =>
    request<OniControlStatus>(`/api/control/status?proxy=${encodeURIComponent(proxy)}`, { signal }),
  oniControlCapabilities: (xuid: string, backend: string, proxy: string, signal?: AbortSignal) =>
    request<{ target: OniControlTarget; actions: OniControlActionCapability[] }>(
      `/api/control/capabilities?xuid=${encodeURIComponent(xuid)}&backend=${encodeURIComponent(backend)}&proxy=${encodeURIComponent(proxy)}`,
      { signal },
    ),
  oniControlPreview: (body: Record<string, string>) =>
    request<OniControlPreview>("/api/control/actions/preview", { method: "POST", body }),
  oniControlExecute: (confirmationToken: string, confirmed: boolean, proxy: string) =>
    request<OniControlResult>("/api/control/actions/execute", {
      method: "POST",
      body: { confirmationToken, confirmed: String(confirmed), proxy },
    }),
  oniControlPlanValidate: (plan: string, proxy: string) =>
    request<OniControlPlanPreview>("/api/control/plans/validate", {
      method: "POST",
      body: { plan, proxy },
    }),
  oniControlPlanPreview: (plan: string, proxy: string) =>
    request<OniControlPlanPreview>("/api/control/plans/preview", {
      method: "POST",
      body: { plan, proxy },
    }),
  oniControlPlanExecute: (confirmationToken: string, confirmed: boolean, proxy: string) =>
    request<OniControlPlanResult>("/api/control/plans/execute", {
      method: "POST",
      body: { confirmationToken, confirmed: String(confirmed), proxy },
    }),
  oniControlHistory: (proxy: string, signal?: AbortSignal) =>
    request<{ history: OniControlHistoryRecord[] }>(
      `/api/control/history?proxy=${encodeURIComponent(proxy)}`,
      { signal },
    ),
  oniPacketRules: (proxy: string, signal?: AbortSignal) =>
    request<{ version: number; rules: unknown[]; statistics?: unknown[] }>(
      `/api/control/rules?proxy=${encodeURIComponent(proxy)}`,
      { signal },
    ),
  saveOniPacketRules: (rules: string, proxy: string) =>
    request<ActionResult>("/api/control/rules", { method: "POST", body: { rules, proxy } }),
  protocolLabStatus: (proxy: string, signal?: AbortSignal) =>
    request<ProtocolLabStatus>(
      `/api/control/protocol-lab/status?proxy=${encodeURIComponent(proxy)}`,
      { signal },
    ),
  protocolLabSession: (start: boolean, proxy: string) =>
    request<{ started?: boolean; stopped?: boolean; expiresAt?: string }>(
      "/api/control/protocol-lab/session",
      { method: start ? "POST" : "DELETE", body: { proxy } },
    ),
  protocolLabValidate: (body: Record<string, string>, send: boolean) =>
    request<ProtocolLabResult>(`/api/control/protocol-lab/${send ? "send" : "validate"}`, {
      method: "POST",
      body,
    }),
  action: (action: string, body: Record<string, string | number>) =>
    request<ActionResult>(`/api/action/${action}`, { method: "POST", body }),
  allowlist: (signal?: AbortSignal) => request<Allowlist>("/api/allowlist", { signal }),
  addAllowlist: (body: Record<string, string>) =>
    request<ActionResult>("/api/allowlist", { method: "POST", body }),
  removeAllowlist: (xuid: string) =>
    request<ActionResult>("/api/allowlist", { method: "DELETE", body: { xuid } }),
  config: (signal?: AbortSignal) => request<Configuration>("/api/config", { signal }),
  saveConfig: (revision: string, content: string) =>
    request<Configuration>("/api/config", { method: "POST", body: { revision, content } }),
  rollbackConfig: () => request<Configuration>("/api/config/rollback", { method: "POST" }),
  addBackend: (body: Record<string, string>) =>
    request<BackendSetup>("/api/config/backends", { method: "POST", body }),
  logs: (limit: number, signal?: AbortSignal) =>
    request<{ lines: string[] }>(`/api/logs?limit=${limit}`, { signal }),
  audit: (limit: number, signal?: AbortSignal) =>
    request<{ lines: string[] }>(`/api/audit?limit=${limit}`, { signal }),
  users: (signal?: AbortSignal) => request<{ users: DashboardUser[] }>("/api/users", { signal }),
  createUser: (body: Record<string, string>) =>
    request<{ created: boolean }>("/api/users", { method: "POST", body }),
  deleteUser: (username: string) =>
    request<{ deleted: boolean }>("/api/users", { method: "DELETE", body: { username } }),
  changePassword: (body: Record<string, string>) =>
    request<{ changed: boolean; sessionEnded: boolean }>("/api/account/password", {
      method: "POST",
      body,
    }),
  beginTotp: () =>
    request<{ secret: string; uri: string }>("/api/account/totp/begin", { method: "POST" }),
  enableTotp: (secret: string, code: string) =>
    request<{ enabled: boolean; sessionEnded: boolean }>("/api/account/totp/enable", {
      method: "POST",
      body: { secret, code },
    }),
  disableTotp: (password: string, code: string) =>
    request<{ disabled: boolean; sessionEnded: boolean }>("/api/account/totp/disable", {
      method: "POST",
      body: { password, code },
    }),
  supportBundle: () => download("/api/support-bundle", `onilink-support-${Date.now()}.zip`),
  shutdown: () =>
    request<ActionResult & { accepted: boolean }>("/api/shutdown", { method: "POST" }),
  tenancy: (signal?: AbortSignal) => request<TenancyOverview>("/api/tenancy", { signal }),
  createTenant: (body: Record<string, string>) =>
    request<{ message: string }>("/api/tenancy/tenants", { method: "POST", body }),
  tenantControlGrants: (tenant: string, signal?: AbortSignal) =>
    request<{ tenant: string; actions: string[] }>(
      `/api/tenancy/control-grants?tenant=${encodeURIComponent(tenant)}`,
      { signal },
    ),
  saveTenantControlGrants: (tenant: string, actions: string[]) =>
    request<{ tenant: string; actions: string[] }>("/api/tenancy/control-grants", {
      method: "POST",
      body: { tenant, grants: JSON.stringify({ actions }) },
    }),
  addTenantUser: (body: Record<string, string>) =>
    request<{ users: DashboardUser[] }>("/api/tenancy/users", { method: "POST", body }),
  createTenantProxy: (body: Record<string, string>) =>
    request<{ message: string }>("/api/tenancy/proxies", { method: "POST", body }),
  tenantAction: (tenant: string, action: string) =>
    request("/api/tenancy/tenant/action", { method: "POST", body: { tenant, action } }),
  tenantProxyAction: (tenant: string, proxy: string, action: string) =>
    request<{ message: string }>("/api/tenancy/proxy/action", {
      method: "POST",
      body: { tenant, proxy, action },
    }),
  tenantProxy: (tenant: string, proxy: string, signal?: AbortSignal) =>
    request<TenantProxyDashboard>(
      `/api/tenancy/proxy?tenant=${encodeURIComponent(tenant)}&proxy=${encodeURIComponent(proxy)}`,
      { signal },
    ),
  tenantRuntimeAction: (body: Record<string, string | number>) =>
    request<ActionResult>("/api/tenancy/proxy/runtime", { method: "POST", body }),
  tenantAllowlist: (tenant: string, proxy: string, signal?: AbortSignal) =>
    request<Allowlist>(
      `/api/tenancy/proxy/allowlist?tenant=${encodeURIComponent(tenant)}&proxy=${encodeURIComponent(proxy)}`,
      { signal },
    ),
  addTenantAllowlist: (body: Record<string, string>) =>
    request<ActionResult>("/api/tenancy/proxy/allowlist", { method: "POST", body }),
  removeTenantAllowlist: (body: Record<string, string>) =>
    request<ActionResult>("/api/tenancy/proxy/allowlist", { method: "DELETE", body }),
  addTenantBackend: (body: Record<string, string>) =>
    request<BackendSetup & { message: string }>("/api/tenancy/proxy/backends", {
      method: "POST",
      body,
    }),
  setTenantPrimaryBackend: (body: Record<string, string>) =>
    request<{
      message: string;
      changed: boolean;
      primaryBackend: string;
      primaryBackendAddress: string;
      proxy: TenantProxyDashboard["proxy"];
    }>("/api/tenancy/proxy/primary-backend", { method: "POST", body }),
  tenantHandoff: (tenant: string, proxy: string) =>
    download(
      `/api/tenancy/handoff?tenant=${encodeURIComponent(tenant)}&proxy=${encodeURIComponent(proxy)}`,
      `${tenant}--${proxy}.handoff.zip`,
    ),
};
