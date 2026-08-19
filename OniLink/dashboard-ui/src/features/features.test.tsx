import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ApiError } from "../api/client";
import { dashboardApi } from "../api/dashboard";
import { App } from "../app/App";
import { AuthProvider } from "../auth/AuthProvider";
import { backends, player, state } from "../test/fixtures";
import type { GlobalRole, Player } from "../types/dashboard";

function renderRoute(role: GlobalRole, route: string) {
  sessionStorage.setItem("onilink_dashboard_token", "test-session");
  window.location.hash = `#/${route}`;
  vi.spyOn(dashboardApi, "whoami").mockResolvedValue({ username: "tester", role, tenantId: "" });
  vi.spyOn(dashboardApi, "state").mockResolvedValue({
    ...state,
    principal: { username: "tester", role, tenantId: "" },
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("monitoring features", () => {
  it("renders the runtime overview using real API values", async () => {
    vi.spyOn(dashboardApi, "backends").mockResolvedValue({ backends });
    renderRoute("viewer", "overview");
    expect(await screen.findByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect(await screen.findByText("1 / 100")).toBeInTheDocument();
    expect(await screen.findByText("1.0 MiB / 4.0 MiB")).toBeInTheDocument();
    expect(screen.getByText("Healthy")).toBeInTheDocument();
    expect(screen.getByText("Degraded")).toBeInTheDocument();
  });

  it("renders backend health and role-restricted endpoints", async () => {
    vi.spyOn(dashboardApi, "backends").mockResolvedValue({ backends });
    renderRoute("admin", "backends");
    expect(await screen.findByText("survival")).toBeInTheDocument();
    expect(screen.getByText("Slow response")).toBeInTheDocument();
    expect(screen.getByText("10.0.0.2:19132")).toBeInTheDocument();
    expect(screen.getByText("Sub-chunks filtered")).toBeInTheDocument();
  });

  it("searches, sorts, transfers, traces, and disconnects players deliberately", async () => {
    const alice: Player = {
      ...player,
      name: "Alice",
      xuid: "123456789",
      identity: "bedrock:alice",
      backend: "creative",
    };
    vi.spyOn(dashboardApi, "players").mockResolvedValue({ players: [player, alice] });
    vi.spyOn(dashboardApi, "backends").mockResolvedValue({ backends });
    const action = vi
      .spyOn(dashboardApi, "action")
      .mockResolvedValue({ success: true, message: "Operation accepted" });
    renderRoute("operator", "players");
    const user = userEvent.setup();
    const search = await screen.findByRole("searchbox", { name: "Search players" });
    await user.type(search, "Alice");
    expect(screen.getByText("Alice")).toBeInTheDocument();
    expect(screen.queryByText("TheN1NJ4LL0")).not.toBeInTheDocument();
    await user.clear(search);
    await user.click(within(screen.getByRole("table")).getByRole("button", { name: /^Player/ }));
    const rows = screen.getAllByRole("row");
    expect(within(rows[1]!).getByText("TheN1NJ4LL0")).toBeInTheDocument();
    const ninjaRow = screen.getByText("TheN1NJ4LL0").closest("tr")!;
    await user.click(within(ninjaRow).getByRole("button", { name: "Transfer" }));
    await user.selectOptions(screen.getByLabelText("Destination backend"), "creative");
    await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
    await waitFor(() =>
      expect(action).toHaveBeenCalledWith("transfer", {
        player: "TheN1NJ4LL0",
        backend: "creative",
      }),
    );
    await user.click(
      within(screen.getByText("TheN1NJ4LL0").closest("tr")!).getByRole("button", { name: "Trace" }),
    );
    await user.click(screen.getByRole("button", { name: "Confirm trace" }));
    await waitFor(() =>
      expect(action).toHaveBeenCalledWith("trace", { player: "TheN1NJ4LL0", milliseconds: 10000 }),
    );
    await user.click(
      within(screen.getByText("TheN1NJ4LL0").closest("tr")!).getByRole("button", {
        name: "Disconnect",
      }),
    );
    await user.click(screen.getByRole("button", { name: "Confirm disconnect" }));
    await waitFor(() =>
      expect(action).toHaveBeenCalledWith("disconnect", {
        player: "TheN1NJ4LL0",
        reason: "Disconnected by an operator",
      }),
    );
  });
});

describe("administrative features", () => {
  it("creates a tenant with its required initial scoped account", async () => {
    vi.spyOn(dashboardApi, "tenancy").mockResolvedValue({
      mode: "single-container",
      providerPort: 19130,
      tenants: [],
      proxies: [],
      tenantScope: "",
    });
    const createTenant = vi
      .spyOn(dashboardApi, "createTenant")
      .mockResolvedValue({ message: "Tenant and account created" });
    renderRoute("owner", "tenant-hosting");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Tenant ID"), "acme");
    await user.type(screen.getByLabelText("Display label"), "Acme Network");
    await user.type(screen.getByLabelText("Initial tenant username"), "acme-admin");
    await user.type(screen.getByLabelText("Initial tenant password"), "secure tenant password");
    await user.click(screen.getByRole("button", { name: "Create tenant" }));
    await waitFor(() =>
      expect(createTenant).toHaveBeenCalledWith({
        tenant: "acme",
        label: "Acme Network",
        username: "acme-admin",
        password: "secure tenant password",
      }),
    );
  });

  it("completes the backend wizard and exposes the one-time bundle", async () => {
    vi.spyOn(dashboardApi, "config").mockResolvedValue({
      path: "config.properties",
      content: "proxy.motd=Test",
      revision: "revision-1",
      backupAvailable: true,
      redactedPlaceholder: "<redacted>",
    });
    const addBackend = vi.spyOn(dashboardApi, "addBackend").mockResolvedValue({
      path: "config.properties",
      content: "proxy.motd=Test",
      revision: "revision-2",
      backupAvailable: true,
      redactedPlaceholder: "<redacted>",
      backendName: "creative",
      secret: "one-time-secret",
      secretFileName: "creative.key",
      onilinkSecretFile: "secrets/creative.key",
      onilinkProperties: "backend.creative.address=10.0.0.3:19132",
      onibridgeToml: 'bridge_id = "creative-main"',
      backendEndpoint: "10.0.0.3:19132",
      trustedProxyCidr: "45.143.196.108/32",
      setupBundleFileName: "creative-setup.zip",
      setupBundleBase64: "WklQ",
      restartRequired: true,
      message: "Backend added",
    });
    renderRoute("admin", "add-backend");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Backend name"), "creative");
    await user.type(screen.getByLabelText("BDS allocation"), "10.0.0.3:19132");
    await user.click(screen.getByRole("button", { name: /Continue/ }));
    await user.type(screen.getByLabelText("OniLink public IP"), "45.143.196.108");
    await user.click(screen.getByRole("button", { name: /Continue/ }));
    await user.click(screen.getByRole("button", { name: /Continue/ }));
    await user.click(screen.getByRole("button", { name: "Create backend setup package" }));
    expect(await screen.findByText("one-time-secret")).toBeInTheDocument();
    expect(addBackend).toHaveBeenCalledWith({
      name: "creative",
      address: "10.0.0.3:19132",
      proxyPublicIp: "45.143.196.108",
      bridgeId: "",
      activeKeyId: "key-1",
      revision: "revision-1",
    });
    await user.click(screen.getByRole("button", { name: "Clear result" }));
    expect(screen.queryByText("one-time-secret")).not.toBeInTheDocument();
  });

  it("adds and removes allowlist entries", async () => {
    const allowlist = {
      enabled: true,
      count: 1,
      disconnectOnRemoval: true,
      entries: [{ xuid: "123456789", name: "Alice" }],
    };
    vi.spyOn(dashboardApi, "allowlist").mockResolvedValue(allowlist);
    vi.spyOn(dashboardApi, "players").mockResolvedValue({ players: [player] });
    const add = vi
      .spyOn(dashboardApi, "addAllowlist")
      .mockResolvedValue({ success: true, message: "Added" });
    const remove = vi
      .spyOn(dashboardApi, "removeAllowlist")
      .mockResolvedValue({ success: true, message: "Removed" });
    renderRoute("admin", "allowlist");
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("XUID"), "2535438695543476");
    await user.type(screen.getByLabelText(/Gamertag label/), "Ninja");
    await user.click(screen.getByRole("button", { name: "Add entry" }));
    await waitFor(() =>
      expect(add).toHaveBeenCalledWith({ xuid: "2535438695543476", name: "Ninja" }),
    );
    await user.click(screen.getByRole("button", { name: "Remove Alice" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("may be disconnected immediately");
    await user.click(screen.getByRole("button", { name: "Remove entry" }));
    await waitFor(() => expect(remove).toHaveBeenCalledWith("123456789"));
  });

  it("saves configuration, identifies 409 conflicts, and confirms rollback", async () => {
    const config = {
      path: "config.properties",
      content: "proxy.motd=Original",
      revision: "abc123",
      backupAvailable: true,
      redactedPlaceholder: "<redacted>",
    };
    vi.spyOn(dashboardApi, "config").mockResolvedValue(config);
    const save = vi
      .spyOn(dashboardApi, "saveConfig")
      .mockRejectedValue(new ApiError("changed", 409, "conflict"));
    const rollback = vi
      .spyOn(dashboardApi, "rollbackConfig")
      .mockResolvedValue({ ...config, revision: "restored" });
    renderRoute("admin", "configuration");
    const user = userEvent.setup();
    const editor = await screen.findByLabelText("Configuration content");
    await user.clear(editor);
    await user.type(editor, "proxy.motd=Updated");
    await user.click(screen.getByRole("button", { name: "Validate and save" }));
    expect(await screen.findByText(/changed on disk/i)).toBeInTheDocument();
    expect(save).toHaveBeenCalledWith("abc123", "proxy.motd=Updated");
    await user.click(screen.getByRole("button", { name: "Restore backup" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await user.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "Restore backup" }),
    );
    await waitFor(() => expect(rollback).toHaveBeenCalledOnce());
  });

  it("accepts a validated configuration save response", async () => {
    const config = {
      path: "config.properties",
      content: "proxy.motd=Original",
      revision: "abc123",
      backupAvailable: true,
      redactedPlaceholder: "<redacted>",
    };
    vi.spyOn(dashboardApi, "config").mockResolvedValue(config);
    const save = vi.spyOn(dashboardApi, "saveConfig").mockResolvedValue({
      ...config,
      content: "proxy.motd=Production",
      revision: "def456",
      restartRequired: true,
      message: "Saved safely",
    });
    renderRoute("admin", "configuration");
    const user = userEvent.setup();
    const editor = await screen.findByLabelText("Configuration content");
    await user.clear(editor);
    await user.type(editor, "proxy.motd=Production");
    await user.click(screen.getByRole("button", { name: "Validate and save" }));
    expect(await screen.findByText("Saved safely")).toBeInTheDocument();
    expect(save).toHaveBeenCalledWith("abc123", "proxy.motd=Production");
  });

  it("downloads support data and requires typed shutdown confirmation", async () => {
    vi.spyOn(dashboardApi, "logs").mockResolvedValue({ lines: ["<img src=x onerror=alert(1)>"] });
    vi.spyOn(dashboardApi, "supportBundle").mockResolvedValue();
    const shutdown = vi
      .spyOn(dashboardApi, "shutdown")
      .mockResolvedValue({ success: true, accepted: true, message: "Stopping" });
    renderRoute("owner", "operations");
    const user = userEvent.setup();
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
    await user.click(screen.getByRole("button", { name: "Download support bundle" }));
    expect(dashboardApi.supportBundle).toHaveBeenCalledOnce();
    const stop = screen.getByRole("button", { name: "Gracefully shut down OniLink" });
    expect(stop).toBeDisabled();
    await user.type(screen.getByLabelText("Confirmation"), "SHUTDOWN");
    await user.click(stop);
    await waitFor(() => expect(shutdown).toHaveBeenCalledOnce());
  });
});
