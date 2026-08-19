import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ApiError } from "../api/client";
import { dashboardApi } from "../api/dashboard";
import { App } from "../app/App";
import { ErrorBoundary } from "../app/ErrorBoundary";
import { visibleNav } from "../layouts/AppShell";
import { backends, state } from "../test/fixtures";
import { AuthProvider } from "./AuthProvider";

function renderApp() {
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

function mockRuntime() {
  vi.spyOn(dashboardApi, "state").mockResolvedValue(state);
  vi.spyOn(dashboardApi, "backends").mockResolvedValue({ backends });
}

describe("authentication and shell", () => {
  it("completes first-run owner setup", async () => {
    mockRuntime();
    vi.spyOn(dashboardApi, "setupStatus").mockResolvedValue({
      setupRequired: true,
      minimumPasswordLength: 12,
      setupFile: "owner-setup.txt",
    });
    const setup = vi.spyOn(dashboardApi, "setup").mockResolvedValue({
      token: "new-session",
      username: "owner",
      role: "owner",
      tenantId: "",
      expiresAt: Date.now() + 1000,
    });
    renderApp();
    const user = userEvent.setup();
    expect(
      await screen.findByRole("heading", { name: /initialize the owner account/i }),
    ).toBeInTheDocument();
    await user.type(screen.getByLabelText(/one-time setup code/i), "SETUP-123");
    await user.type(screen.getByLabelText(/owner username/i), "owner");
    await user.type(screen.getByLabelText(/owner password/i), "correct horse battery staple");
    await user.click(screen.getByRole("button", { name: /create owner/i }));
    await screen.findByRole("heading", { name: "Overview" });
    expect(setup).toHaveBeenCalledWith({
      setupCode: "SETUP-123",
      username: "owner",
      password: "correct horse battery staple",
    });
  });

  it("handles normal login and a conditional TOTP challenge", async () => {
    mockRuntime();
    vi.spyOn(dashboardApi, "setupStatus").mockResolvedValue({
      setupRequired: false,
      minimumPasswordLength: 12,
      setupFile: "owner-setup.txt",
    });
    const login = vi
      .spyOn(dashboardApi, "login")
      .mockRejectedValueOnce(
        new ApiError("Authenticator code required", 401, "unauthorized", { totpRequired: true }),
      )
      .mockResolvedValueOnce({
        token: "session",
        username: "owner",
        role: "owner",
        tenantId: "",
        expiresAt: Date.now() + 1000,
      });
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Username"), "owner");
    await user.type(screen.getByLabelText("Password"), "password");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByLabelText("Authenticator code")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Authenticator code"), "123456");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    await screen.findByRole("heading", { name: "Overview" });
    expect(login).toHaveBeenLastCalledWith({
      username: "owner",
      password: "password",
      totp: "123456",
    });
  });

  it("communicates login rate limiting", async () => {
    vi.spyOn(dashboardApi, "setupStatus").mockResolvedValue({
      setupRequired: false,
      minimumPasswordLength: 12,
      setupFile: "owner-setup.txt",
    });
    vi.spyOn(dashboardApi, "login").mockRejectedValue(new ApiError("limited", 429, "rate-limited"));
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Username"), "owner");
    await user.type(screen.getByLabelText("Password"), "bad");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Too many attempts");
  });

  it("restores and logs out a session", async () => {
    sessionStorage.setItem("onilink_dashboard_token", "restored");
    vi.spyOn(dashboardApi, "whoami").mockResolvedValue({
      username: "operator",
      role: "operator",
      tenantId: "",
    });
    mockRuntime();
    const logout = vi.spyOn(dashboardApi, "logout").mockResolvedValue({ loggedOut: true });
    renderApp();
    const user = userEvent.setup();
    await screen.findByRole("heading", { name: "Overview" });
    await user.click(screen.getByRole("button", { name: /sign out/i }));
    expect(await screen.findByRole("heading", { name: /sign in to onilink/i })).toBeInTheDocument();
    expect(logout).toHaveBeenCalledOnce();
    expect(sessionStorage.getItem("onilink_dashboard_token")).toBeNull();
  });

  it("exposes navigation according to every role", () => {
    expect(visibleNav("viewer").map((item) => item.label)).toEqual([
      "Overview",
      "Players",
      "Backends",
      "Packet Monitor",
      "Account",
    ]);
    expect(visibleNav("operator").map((item) => item.label)).toContain("Operations");
    expect(visibleNav("admin").map((item) => item.label)).toEqual(
      expect.arrayContaining(["Add Backend", "Allowlist", "Configuration", "Audit"]),
    );
    expect(visibleNav("owner").map((item) => item.label)).toContain("Tenant Hosting");
    expect(visibleNav("tenant").map((item) => item.label)).toEqual([
      "Packet Monitor",
      "My Proxies",
      "Account",
    ]);
  });

  it("opens and cleans up the mobile drawer with Escape and backdrop input", async () => {
    sessionStorage.setItem("onilink_dashboard_token", "restored");
    vi.spyOn(dashboardApi, "whoami").mockResolvedValue({
      username: "viewer",
      role: "viewer",
      tenantId: "",
    });
    mockRuntime();
    renderApp();
    const user = userEvent.setup();
    const menu = await screen.findByRole("button", { name: "Open navigation" });
    await user.click(menu);
    expect(document.body).toHaveClass("drawerOpen");
    expect(screen.getByLabelText("Primary navigation")).toHaveClass("open");
    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(document.body).not.toHaveClass("drawerOpen"));
    await user.click(menu);
    await user.click(document.querySelector<HTMLButtonElement>(".drawerBackdrop")!);
    expect(document.body).not.toHaveClass("drawerOpen");
    expect(screen.getByLabelText("Primary navigation")).not.toHaveClass("open");
  });
});

describe("rendering safety", () => {
  it("shows hostile values as plain text", async () => {
    sessionStorage.setItem("onilink_dashboard_token", "restored");
    vi.spyOn(dashboardApi, "whoami").mockResolvedValue({
      username: "<script>alert(1)</script>",
      role: "viewer",
      tenantId: "",
    });
    mockRuntime();
    renderApp();
    expect(await screen.findByText("<script>alert(1)</script>")).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();
  });

  it("contains render failures in the error boundary", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    function Broken(): never {
      throw new Error("boom");
    }
    render(
      <ErrorBoundary>
        <Broken />
      </ErrorBoundary>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Control plane unavailable");
  });
});
