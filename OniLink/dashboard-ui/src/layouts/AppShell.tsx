import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  BookOpenCheck,
  Boxes,
  Cable,
  ChevronLeft,
  CircleUserRound,
  KeyRound,
  LayoutDashboard,
  ListChecks,
  LogOut,
  Menu,
  Network,
  ScanSearch,
  ServerCog,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  UsersRound,
  Wrench,
  Workflow,
  X,
} from "lucide-react";
import { useEffect, useRef, useState, type ComponentType, type PropsWithChildren } from "react";
import { dashboardApi } from "../api/dashboard";
import { BrandMark } from "../components/BrandMark";
import { Button, Status } from "../components/ui";
import { useAuth } from "../auth/AuthProvider";
import { hasRole } from "../permissions/roles";
import type { Role } from "../types/dashboard";
import { timestamp } from "../utilities/format";

interface NavItem {
  route: string;
  label: string;
  icon: ComponentType<{ "aria-hidden"?: boolean }>;
  minimum?: "viewer" | "operator" | "admin" | "owner";
  tenant?: boolean;
  allRoles?: boolean;
}
const groups: Array<{ label: string; items: NavItem[] }> = [
  {
    label: "Monitor",
    items: [
      { route: "overview", label: "Overview", icon: LayoutDashboard, minimum: "viewer" },
      { route: "players", label: "Players", icon: UsersRound, minimum: "viewer" },
      { route: "backends", label: "Backends", icon: Network, minimum: "viewer" },
      { route: "packet-monitor", label: "Packet Monitor", icon: ScanSearch, allRoles: true },
      { route: "onicontrol", label: "OniControl", icon: SlidersHorizontal, allRoles: true },
      { route: "platform", label: "Platform", icon: Workflow, allRoles: true },
    ],
  },
  {
    label: "Manage",
    items: [
      { route: "add-backend", label: "Add Backend", icon: Cable, minimum: "admin" },
      { route: "allowlist", label: "Allowlist", icon: ListChecks, minimum: "admin" },
      { route: "configuration", label: "Configuration", icon: Settings, minimum: "admin" },
      { route: "operations", label: "Operations", icon: Wrench, minimum: "operator" },
    ],
  },
  {
    label: "Governance",
    items: [
      { route: "audit", label: "Audit", icon: BookOpenCheck, minimum: "admin" },
      { route: "tenant-hosting", label: "Tenant Hosting", icon: ServerCog, minimum: "owner" },
      { route: "my-proxies", label: "My Proxies", icon: Boxes, tenant: true },
      { route: "account", label: "Account", icon: CircleUserRound, allRoles: true },
    ],
  },
];

export function visibleNav(role: Role): NavItem[] {
  return groups
    .flatMap((group) => group.items)
    .filter((item) =>
      item.allRoles
        ? true
        : item.tenant
          ? role === "tenant"
          : role !== "tenant" && (!item.minimum || hasRole(role, item.minimum)),
    );
}

export function AppShell({
  route,
  navigate,
  children,
}: PropsWithChildren<{ route: string; navigate: (route: string) => void }>) {
  const { principal, signOut } = useAuth();
  const [drawer, setDrawer] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const state = useQuery({
    queryKey: ["state"],
    queryFn: ({ signal }) => dashboardApi.state(signal),
    enabled: principal?.role !== "tenant",
    refetchInterval: 5_000,
    retry: 1,
  });
  const role = principal?.role ?? "viewer";
  const items = visibleNav(role);

  useEffect(() => {
    if (!drawer) return;
    const opener = menuButton.current;
    document.body.classList.add("drawerOpen");
    closeButton.current?.focus();
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setDrawer(false);
    };
    window.addEventListener("keydown", escape);
    return () => {
      document.body.classList.remove("drawerOpen");
      window.removeEventListener("keydown", escape);
      opener?.focus();
    };
  }, [drawer]);

  function go(next: string) {
    navigate(next);
    setDrawer(false);
  }
  return (
    <div className="appShell">
      <button
        className={`drawerBackdrop ${drawer ? "visible" : ""}`}
        aria-label="Close navigation"
        tabIndex={drawer ? 0 : -1}
        onClick={() => setDrawer(false)}
      />
      <aside className={`sidebar ${drawer ? "open" : ""}`} aria-label="Primary navigation">
        <div className="sidebarBrand">
          <BrandMark />
          <span>
            <strong>OniLink</strong>
            <small>Bedrock Edge System</small>
          </span>
          <button
            ref={closeButton}
            className="iconButton drawerClose"
            aria-label="Close navigation"
            onClick={() => setDrawer(false)}
          >
            <X aria-hidden="true" />
          </button>
        </div>
        <nav>
          {groups.map((group) => {
            const allowed = group.items.filter((item) =>
              items.some((candidate) => candidate.route === item.route),
            );
            return allowed.length ? (
              <section key={group.label}>
                <h2>{group.label}</h2>
                {allowed.map((item) => (
                  <button
                    key={item.route}
                    className={route === item.route ? "active" : ""}
                    aria-current={route === item.route ? "page" : undefined}
                    onClick={() => go(item.route)}
                  >
                    <item.icon aria-hidden={true} />
                    <span>{item.label}</span>
                  </button>
                ))}
              </section>
            ) : null;
          })}
        </nav>
        <div className="sidebarFoot">
          <ShieldCheck aria-hidden="true" />
          <span>
            <strong>Local control</strong>
            <small>No external telemetry</small>
          </span>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <button
            ref={menuButton}
            className="iconButton menuButton"
            aria-label="Open navigation"
            aria-expanded={drawer}
            onClick={() => setDrawer(true)}
          >
            <Menu aria-hidden="true" />
          </button>
          <div className="connection">
            {principal?.role === "tenant" ? (
              <Status state="neutral">Tenant scope</Status>
            ) : state.isError ? (
              <Status state="danger">Disconnected</Status>
            ) : state.isFetching && !state.data ? (
              <Status state="warning">Connecting</Status>
            ) : (
              <Status state="ok">Proxy online</Status>
            )}
            <span className="refreshTime">
              {state.dataUpdatedAt
                ? `Refreshed ${timestamp(state.dataUpdatedAt)}`
                : "Awaiting status"}
            </span>
          </div>
          <div className="identity">
            <KeyRound aria-hidden="true" />
            <span>
              <strong>{principal?.username}</strong>
              <small>{role}</small>
            </span>
            <Button className="secondary signOut" onClick={() => void signOut()}>
              <LogOut aria-hidden="true" />
              Sign out
            </Button>
          </div>
        </header>
        <main id="main-content" className="content">
          {children}
        </main>
      </div>
    </div>
  );
}

export function ForbiddenPage({ onBack }: { onBack: () => void }) {
  return (
    <section className="centerState">
      <Activity aria-hidden="true" />
      <h1>Page unavailable</h1>
      <p>Your dashboard role does not include this control.</p>
      <Button onClick={onBack}>
        <ChevronLeft aria-hidden="true" />
        Return to overview
      </Button>
    </section>
  );
}
