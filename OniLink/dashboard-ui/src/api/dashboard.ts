import { download, request } from "./client";
import type {
  ActionResult,
  Allowlist,
  Backend,
  BackendSetup,
  Configuration,
  DashboardUser,
  Player,
  Principal,
  RuntimeState,
  SessionPayload,
  SetupStatus,
  TenancyOverview,
  TenantProxyDashboard,
} from "../types/dashboard";

export const dashboardApi = {
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
  backends: (signal?: AbortSignal) => request<{ backends: Backend[] }>("/api/backends", { signal }),
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
  tenantHandoff: (tenant: string, proxy: string) =>
    download(
      `/api/tenancy/handoff?tenant=${encodeURIComponent(tenant)}&proxy=${encodeURIComponent(proxy)}`,
      `${tenant}--${proxy}.handoff.zip`,
    ),
};
