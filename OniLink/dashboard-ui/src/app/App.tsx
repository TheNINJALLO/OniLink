import { AuthPage } from "../auth/AuthPage";
import { useAuth } from "../auth/AuthProvider";
import { Loading } from "../components/ui";
import { AccountPage } from "../features/account/AccountPage";
import { AllowlistPage } from "../features/allowlist/AllowlistPage";
import { AuditPage } from "../features/audit/AuditPage";
import { BackendsPage } from "../features/backends/BackendsPage";
import { AddBackendPage } from "../features/configuration/AddBackendPage";
import { ConfigurationPage } from "../features/configuration/ConfigurationPage";
import { TenantHostingPage } from "../features/hosting/TenantHostingPage";
import { TenantPortalPage } from "../features/hosting/TenantPortalPage";
import { OperationsPage } from "../features/operations/OperationsPage";
import { OverviewPage } from "../features/overview/OverviewPage";
import { PlayersPage } from "../features/players/PlayersPage";
import { useHashRoute } from "../hooks/useHashRoute";
import { AppShell, ForbiddenPage, visibleNav } from "../layouts/AppShell";

export function App() {
  const { principal, restoring } = useAuth();
  const [route, navigate] = useHashRoute(principal?.role === "tenant" ? "my-proxies" : "overview");
  if (restoring)
    return (
      <main className="boot">
        <Loading label="Restoring secure session" />
      </main>
    );
  if (!principal) return <AuthPage />;
  const allowed =
    visibleNav(principal.role).some((item) => item.route === route) ||
    (principal.role === "owner" && route === "my-proxies");
  let page = (
    <ForbiddenPage
      onBack={() => navigate(principal.role === "tenant" ? "my-proxies" : "overview")}
    />
  );
  if (allowed) {
    switch (route) {
      case "overview":
        page = <OverviewPage />;
        break;
      case "players":
        page = <PlayersPage />;
        break;
      case "backends":
        page = <BackendsPage navigate={navigate} />;
        break;
      case "add-backend":
        page = <AddBackendPage />;
        break;
      case "allowlist":
        page = <AllowlistPage />;
        break;
      case "configuration":
        page = <ConfigurationPage />;
        break;
      case "operations":
        page = <OperationsPage />;
        break;
      case "audit":
        page = <AuditPage />;
        break;
      case "tenant-hosting":
        page = <TenantHostingPage navigate={navigate} />;
        break;
      case "my-proxies":
        page = <TenantPortalPage />;
        break;
      case "account":
        page = <AccountPage />;
        break;
    }
  }
  return (
    <AppShell route={route} navigate={navigate}>
      {page}
    </AppShell>
  );
}
