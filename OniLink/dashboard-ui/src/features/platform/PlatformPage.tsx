import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  BellRing,
  Boxes,
  GitCompareArrows,
  ListTodo,
  Network,
  PackageSearch,
  RefreshCw,
  Route,
  ShieldAlert,
  TicketCheck,
  UsersRound,
} from "lucide-react";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Card, Empty, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { hasRole } from "../../permissions/roles";

interface ModuleView {
  id: string;
  version: string;
  enabled: boolean;
  health: string;
  message: string;
  dependencies: string[];
}

interface ModulesResponse {
  modules: ModuleView[];
  eventBus: Record<string, number>;
  actions: Array<Record<string, unknown>>;
  cachedAt?: string;
}

type Section =
  | "platform"
  | "flow"
  | "continuity"
  | "sentinel"
  | "pulse"
  | "forge"
  | "fleet"
  | "presence"
  | "roles"
  | "support"
  | "packs"
  | "notifications";

const sections: Array<{
  id: Section;
  label: string;
  module: string;
  icon: typeof Activity;
  path: string;
}> = [
  {
    id: "platform",
    label: "Modules",
    module: "shared-platform",
    icon: Boxes,
    path: "/api/modules",
  },
  { id: "flow", label: "OniFlow", module: "flow", icon: ListTodo, path: "/api/flow/workflows" },
  {
    id: "continuity",
    label: "Continuity",
    module: "continuity",
    icon: Route,
    path: "/api/continuity/backends",
  },
  {
    id: "sentinel",
    label: "Quarantine",
    module: "sentinel",
    icon: ShieldAlert,
    path: "/api/security/quarantine",
  },
  { id: "pulse", label: "Journeys", module: "pulse", icon: Activity, path: "/api/journeys" },
  {
    id: "forge",
    label: "Compatibility",
    module: "forge",
    icon: GitCompareArrows,
    path: "/api/compatibility/matrix",
  },
  { id: "fleet", label: "Fleet", module: "fleet", icon: Network, path: "/api/fleet/backends" },
  { id: "presence", label: "Presence", module: "connect", icon: UsersRound, path: "/api/presence" },
  { id: "roles", label: "Global roles", module: "connect", icon: UsersRound, path: "/api/roles" },
  {
    id: "support",
    label: "Support",
    module: "connect",
    icon: TicketCheck,
    path: "/api/support/tickets",
  },
  {
    id: "packs",
    label: "Pack scanner",
    module: "packs",
    icon: PackageSearch,
    path: "/api/packs/scans",
  },
  {
    id: "notifications",
    label: "Notifications",
    module: "notifications",
    icon: BellRing,
    path: "/api/notifications/subscriptions",
  },
];

export function PlatformPage() {
  const { principal } = useAuth();
  const client = useQueryClient();
  const [section, setSection] = useState<Section>("platform");
  const [proxy, setProxy] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const connected = () => setOnline(true);
    const disconnected = () => setOnline(false);
    window.addEventListener("online", connected);
    window.addEventListener("offline", disconnected);
    return () => {
      window.removeEventListener("online", connected);
      window.removeEventListener("offline", disconnected);
    };
  }, []);
  const modules = useQuery({
    queryKey: ["platform-modules"],
    queryFn: ({ signal }) => loadModuleHealth(signal),
    refetchInterval: 10_000,
  });
  const tenancy = useQuery({
    queryKey: ["platform-tenant-proxies"],
    queryFn: ({ signal }) => dashboardApi.tenancy(signal),
    enabled: principal?.role === "tenant",
  });
  const tenantProxies = tenancy.data?.proxies ?? [];
  const selectedProxy = proxy || tenantProxies[0]?.id || "";
  const scope = useMemo<Record<string, string>>(() => {
    const value: Record<string, string> = {};
    if (principal?.role === "tenant" && selectedProxy) value.proxy = selectedProxy;
    return value;
  }, [principal?.role, selectedProxy]);
  const selected = sections.find((item) => item.id === section) ?? sections[0]!;
  const module = modules.data?.modules.find((item) => item.id === selected.module);
  const detail = useQuery({
    queryKey: ["platform-section", section, scope],
    queryFn: ({ signal }) =>
      dashboardApi.platformGet<Record<string, unknown>>(selected.path, scope, signal),
    enabled:
      section !== "platform" &&
      Boolean(module?.enabled) &&
      (principal?.role !== "tenant" || Boolean(selectedProxy)),
    refetchInterval: section === "pulse" || section === "presence" ? 5_000 : false,
  });
  const mutate = useMutation({
    mutationFn: (operation: {
      path: string;
      method?: "POST" | "PUT" | "DELETE";
      body?: Record<string, string | number | boolean>;
    }) =>
      dashboardApi.platformMutation<Record<string, unknown>>(
        operation.path,
        operation.method ?? "POST",
        { ...operation.body, ...scope },
      ),
    onSuccess: async () => {
      setError("");
      setMessage("Operation completed and was written to the audit trail.");
      await client.invalidateQueries({ queryKey: ["platform-section"] });
      await client.invalidateQueries({ queryKey: ["platform-modules"] });
    },
    onError: (failure: Error) => {
      setMessage("");
      setError(failure.message);
    },
  });
  const canManage =
    principal?.role === "tenant" || (principal ? hasRole(principal.role, "admin") : false);

  function submit(
    path: string,
    body: Record<string, string | number | boolean>,
    method?: "POST" | "PUT" | "DELETE",
  ) {
    setMessage("");
    setError("");
    mutate.mutate({ path, body, method });
  }

  return (
    <>
      <PageHeader
        title="OniLink platform"
        description="Tenant-isolated workflows, continuity, deployments, security, support, compatibility evidence, and mobile operations."
        actions={
          <Button
            className="secondary"
            onClick={() => void client.invalidateQueries({ queryKey: ["platform-section"] })}
          >
            <RefreshCw aria-hidden="true" /> Refresh
          </Button>
        }
      />
      {principal?.role === "tenant" ? (
        <label className="proxySelector">
          Proxy to operate
          <select value={selectedProxy} onChange={(event) => setProxy(event.target.value)}>
            {tenantProxies.map((item) => (
              <option key={item.id} value={item.id}>
                {item.label} — UDP {item.port}
              </option>
            ))}
          </select>
          <small className="fieldHelp">
            Every record and live action is restricted to this proxy.
          </small>
        </label>
      ) : null}
      <Notice message={message} />
      <Notice message={error} error />
      {!online ? (
        <Notice
          message={
            modules.data?.cachedAt
              ? `Offline — showing the last safe module-health snapshot (${modules.data.cachedAt}). Mutations are unavailable.`
              : "Offline — live operations are unavailable."
          }
        />
      ) : null}
      <nav className="platformTabs" aria-label="Expansion modules">
        {sections.map((item) => {
          const available =
            modules.data?.modules.find((candidate) => candidate.id === item.module)?.enabled ??
            false;
          return (
            <button
              key={item.id}
              className={section === item.id ? "active" : ""}
              onClick={() => setSection(item.id)}
            >
              <item.icon aria-hidden="true" />
              <span>{item.label}</span>
              {item.id !== "platform" ? (
                <i
                  className={available ? "ready" : "disabled"}
                  aria-label={available ? "enabled" : "disabled"}
                />
              ) : null}
            </button>
          );
        })}
      </nav>
      {modules.isLoading ? <Loading label="Loading module registry" /> : null}
      {modules.isError ? <Notice message={modules.error.message} error /> : null}
      {section === "platform" && modules.data ? <ModuleGrid data={modules.data} /> : null}
      {section !== "platform" && module && !module.enabled ? (
        <Card>
          <Empty
            title={`${selected.label} is disabled`}
            detail={`Set modules.${selected.module === "packs" ? "packs.scanner" : selected.module}.enabled=true, then restart OniLink.`}
          />
        </Card>
      ) : null}
      {detail.isLoading ? <Loading label={`Loading ${selected.label}`} /> : null}
      {detail.isError ? <Notice message={detail.error.message} error /> : null}
      {detail.data ? (
        <SectionPanel
          section={section}
          data={detail.data}
          canManage={canManage}
          busy={mutate.isPending}
          submit={submit}
        />
      ) : null}
    </>
  );
}

function ModuleGrid({ data }: { data: ModulesResponse }) {
  return (
    <>
      <div className="metricGrid platformMetrics">
        <Card className="metric">
          <Activity aria-hidden="true" />
          <span>Events accepted</span>
          <strong>{data.eventBus.accepted ?? 0}</strong>
        </Card>
        <Card className="metric">
          <ShieldAlert aria-hidden="true" />
          <span>Events dropped</span>
          <strong>{data.eventBus.dropped ?? 0}</strong>
        </Card>
        <Card className="metric">
          <ListTodo aria-hidden="true" />
          <span>Typed actions</span>
          <strong>{data.actions.length}</strong>
        </Card>
      </div>
      <div className="moduleGrid">
        {data.modules.map((module) => (
          <Card key={module.id}>
            <div className="moduleHeading">
              <div>
                <h2>{module.id}</h2>
                <small>Module API v{module.version}</small>
              </div>
              <Status
                state={module.health === "HEALTHY" ? "ok" : module.enabled ? "danger" : "neutral"}
              >
                {module.health.toLowerCase()}
              </Status>
            </div>
            <p>{module.message}</p>
            <small className="fieldHelp">
              Dependencies: {module.dependencies.join(", ") || "none"}
            </small>
          </Card>
        ))}
      </div>
    </>
  );
}

function SectionPanel({
  section,
  data,
  canManage,
  busy,
  submit,
}: {
  section: Section;
  data: Record<string, unknown>;
  canManage: boolean;
  busy: boolean;
  submit: (
    path: string,
    body: Record<string, string | number | boolean>,
    method?: "POST" | "PUT" | "DELETE",
  ) => void;
}) {
  return (
    <div className="platformContent">
      {section === "continuity" && canManage ? (
        <ContinuityForm busy={busy} submit={submit} />
      ) : null}
      {section === "sentinel" && canManage ? <QuarantineForm busy={busy} submit={submit} /> : null}
      {section === "flow" && canManage ? (
        <>
          <JsonForm kind="workflow" path="/api/flow/workflows" busy={busy} submit={submit} />
          <FlowOperationsForm busy={busy} submit={submit} />
        </>
      ) : null}
      {section === "fleet" && canManage ? (
        <FleetForm busy={busy} data={data} submit={submit} />
      ) : null}
      {section === "roles" && canManage ? (
        <JsonForm kind="role" path="/api/roles" busy={busy} submit={submit} />
      ) : null}
      {section === "support" ? <SupportForm busy={busy} submit={submit} /> : null}
      {section === "packs" && canManage ? <PackForm busy={busy} submit={submit} /> : null}
      {section === "notifications" ? (
        <NotificationForm busy={busy} data={data} submit={submit} />
      ) : null}
      <Card className="platformData">
        <div className="moduleHeading">
          <h2>Current scoped data</h2>
          <Status state="ok">live</Status>
        </div>
        <pre>{JSON.stringify(data, null, 2)}</pre>
      </Card>
    </div>
  );
}

function ContinuityForm({ busy, submit }: FormProps) {
  const [backend, setBackend] = useState("");
  return (
    <Card>
      <h2>Drain or return a backend</h2>
      <p>
        New joins stop first. Existing players receive durable return reservations before moving to
        limbo.
      </p>
      <div className="inlineForm">
        <label>
          Backend name
          <input value={backend} onChange={(event) => setBackend(event.target.value)} />
        </label>
        <Button
          disabled={busy || !backend}
          onClick={() => {
            if (window.confirm(`Stop new joins and move players from ${backend} to limbo?`)) {
              submit(`/api/continuity/backends/${encodeURIComponent(backend)}/drain`, {});
            }
          }}
        >
          Drain to limbo
        </Button>
        <Button
          className="secondary"
          disabled={busy || !backend}
          onClick={() =>
            submit(`/api/continuity/backends/${encodeURIComponent(backend)}/return`, {})
          }
        >
          Return players
        </Button>
      </div>
    </Card>
  );
}

function QuarantineForm({ busy, submit }: FormProps) {
  const [xuid, setXuid] = useState("");
  const [reason, setReason] = useState("");
  return (
    <Card>
      <h2>Quarantine assignment</h2>
      <p>Uses the authenticated XUID. A healthy configured quarantine backend is required.</p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit("/api/security/quarantine", { xuid, reason });
        }}
      >
        <div className="formGrid">
          <label>
            Authenticated XUID
            <input value={xuid} onChange={(event) => setXuid(event.target.value)} />
          </label>
          <label>
            Reason
            <input value={reason} onChange={(event) => setReason(event.target.value)} />
          </label>
        </div>
        <Button disabled={busy || !xuid || !reason}>Assign quarantine</Button>
        <Button
          type="button"
          className="secondary"
          disabled={busy || !xuid}
          onClick={() => submit("/api/security/quarantine", { xuid }, "DELETE")}
        >
          Release
        </Button>
      </form>
    </Card>
  );
}

function JsonForm({
  kind,
  path,
  busy,
  submit,
}: FormProps & { kind: "workflow" | "role"; path: string }) {
  const initial =
    kind === "workflow"
      ? {
          name: "Notify operators",
          enabled: false,
          trigger: "MANUAL",
          steps: [
            {
              type: "ACTION",
              action: "REQUEST_PUSH_NOTIFICATION",
              input: { user: "owner", summary: "Workflow test" },
            },
          ],
        }
      : {
          name: "Trusted Builder",
          description: "Example scoped role",
          permissions: ["support.create", "presence.view"],
        };
  const [value, setValue] = useState(JSON.stringify(initial, null, 2));
  return (
    <Card>
      <h2>Create or revise {kind}</h2>
      <p>The complete definition is validated before its revision is stored.</p>
      <textarea
        className="platformEditor"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        spellCheck={false}
      />
      <Button disabled={busy} onClick={() => submit(path, { [kind]: value })}>
        Validate and save
      </Button>
    </Card>
  );
}

function FleetForm({ busy, data, submit }: FormProps & { data: Record<string, unknown> }) {
  const registry = data.registry as { revision?: number } | undefined;
  const [fields, setFields] = useState({
    name: "",
    host: "",
    port: "19132",
    protocol: "auto",
    proxyId: "edge-1",
    bridgeId: "",
    keyId: "control-key-1",
    secretEnvironment: "",
  });
  const [deploymentId, setDeploymentId] = useState("");
  const [deploymentRevision, setDeploymentRevision] = useState("1");
  const [canary, setCanary] = useState({ xuid: "", backend: "", percentage: "10" });
  const [managedBackend, setManagedBackend] = useState({ name: "", recordRevision: "1" });
  return (
    <Card>
      <h2>Register a running backend</h2>
      <p>
        This changes the live registry atomically. Store only an environment or protected-file
        reference—never the secret value.
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit("/api/fleet/backends", { ...fields, revision: registry?.revision ?? -1 });
        }}
      >
        <div className="formGrid">
          {Object.entries(fields).map(([key, value]) => (
            <label key={key}>
              {friendly(key)}
              <input
                value={value}
                onChange={(event) =>
                  setFields((current) => ({ ...current, [key]: event.target.value }))
                }
              />
            </label>
          ))}
        </div>
        <Button disabled={busy}>Register backend</Button>
      </form>
      <hr />
      <h3>Validate or change a registered backend</h3>
      <p>
        Validation checks the live RakNet health result, advertised protocol, and OniControl bridge
        capability revision. Disabling stops new joins without deleting the saved definition.
      </p>
      <div className="inlineForm">
        <label>
          Registered backend name
          <input
            value={managedBackend.name}
            onChange={(event) =>
              setManagedBackend((current) => ({ ...current, name: event.target.value }))
            }
          />
        </label>
        <label>
          Saved record revision
          <input
            value={managedBackend.recordRevision}
            onChange={(event) =>
              setManagedBackend((current) => ({ ...current, recordRevision: event.target.value }))
            }
          />
        </label>
        <Button
          className="secondary"
          disabled={busy || !managedBackend.name}
          onClick={() =>
            submit(
              `/api/fleet/backends/${encodeURIComponent(managedBackend.name)}/validate`,
              {},
              "POST",
            )
          }
        >
          Run validation
        </Button>
        <Button
          className="secondary"
          disabled={busy || !managedBackend.name}
          onClick={() =>
            submit(`/api/fleet/backends/${encodeURIComponent(managedBackend.name)}/state`, {
              enabled: false,
              runtimeRevision: registry?.revision ?? -1,
              recordRevision: Number(managedBackend.recordRevision),
            })
          }
        >
          Disable new joins
        </Button>
        <Button
          className="secondary"
          disabled={busy || !managedBackend.name}
          onClick={() => {
            if (
              window.confirm(`Restore the previous saved definition for ${managedBackend.name}?`)
            ) {
              submit(`/api/fleet/backends/${encodeURIComponent(managedBackend.name)}/rollback`, {
                runtimeRevision: registry?.revision ?? -1,
                recordRevision: Number(managedBackend.recordRevision),
                confirmed: true,
              });
            }
          }}
        >
          Roll back definition
        </Button>
      </div>
      <hr />
      <h3>Sticky canary assignment</h3>
      <p>
        Eligible new joins stay on the candidate for the configured window. Quarantine always takes
        priority, and Stop Canary disables all canary routes immediately.
      </p>
      <div className="inlineForm">
        <label>
          Test player XUID
          <input
            value={canary.xuid}
            onChange={(event) => setCanary((current) => ({ ...current, xuid: event.target.value }))}
          />
        </label>
        <label>
          Candidate backend
          <input
            value={canary.backend}
            onChange={(event) =>
              setCanary((current) => ({ ...current, backend: event.target.value }))
            }
          />
        </label>
        <label>
          Eligible percentage
          <input
            type="number"
            min="0"
            max="100"
            value={canary.percentage}
            onChange={(event) =>
              setCanary((current) => ({ ...current, percentage: event.target.value }))
            }
          />
        </label>
        <Button
          disabled={busy || !canary.xuid || !canary.backend}
          onClick={() =>
            submit("/api/fleet/canaries", {
              xuid: canary.xuid,
              backend: canary.backend,
              percentage: Number(canary.percentage),
              testAccount: true,
            })
          }
        >
          Assign test account
        </Button>
        <Button
          className="secondary"
          disabled={busy || !canary.xuid}
          onClick={() => submit("/api/fleet/canaries/opt-out", { xuid: canary.xuid })}
        >
          Opt out
        </Button>
      </div>
      <hr />
      <h3>Blue/green decision</h3>
      <div className="inlineForm">
        <label>
          Deployment ID
          <input value={deploymentId} onChange={(event) => setDeploymentId(event.target.value)} />
        </label>
        <label>
          Revision
          <input
            value={deploymentRevision}
            onChange={(event) => setDeploymentRevision(event.target.value)}
          />
        </label>
        <Button
          disabled={busy || !deploymentId}
          onClick={() => {
            if (window.confirm("Promote the validated green backend for new joins?")) {
              submit(`/api/fleet/deployments/${encodeURIComponent(deploymentId)}/promote`, {
                revision: Number(deploymentRevision),
              });
            }
          }}
        >
          Promote green
        </Button>
        <Button
          className="secondary"
          disabled={busy || !deploymentId}
          onClick={() => {
            if (window.confirm("Roll new joins back to the blue backend?")) {
              submit(`/api/fleet/deployments/${encodeURIComponent(deploymentId)}/rollback`, {
                revision: Number(deploymentRevision),
              });
            }
          }}
        >
          Roll back to blue
        </Button>
      </div>
    </Card>
  );
}

function SupportForm({ busy, submit }: FormProps) {
  const [xuid, setXuid] = useState("");
  const [message, setMessage] = useState("");
  const [ticketId, setTicketId] = useState("");
  const [revision, setRevision] = useState("1");
  const [reply, setReply] = useState("");
  return (
    <Card>
      <h2>Create a support ticket</h2>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit("/api/support/tickets", { xuid, message, category: "general" });
        }}
      >
        <div className="formGrid">
          <label>
            Authenticated XUID
            <input value={xuid} onChange={(event) => setXuid(event.target.value)} />
          </label>
          <label>
            Player message
            <textarea value={message} onChange={(event) => setMessage(event.target.value)} />
          </label>
        </div>
        <Button disabled={busy || !xuid || !message}>Create ticket</Button>
      </form>
      <hr />
      <h3>Reply to a ticket</h3>
      <div className="formGrid">
        <label>
          Ticket ID
          <input value={ticketId} onChange={(event) => setTicketId(event.target.value)} />
        </label>
        <label>
          Revision
          <input value={revision} onChange={(event) => setRevision(event.target.value)} />
        </label>
        <label>
          Reply
          <textarea value={reply} onChange={(event) => setReply(event.target.value)} />
        </label>
      </div>
      <Button
        disabled={busy || !ticketId || !reply}
        onClick={() =>
          submit(`/api/support/tickets/${encodeURIComponent(ticketId)}/reply`, {
            revision: Number(revision),
            reply,
          })
        }
      >
        Add reply
      </Button>
    </Card>
  );
}

function PackForm({ busy, submit }: FormProps) {
  const [file, setFile] = useState<File | null>(null);
  async function scan(event: FormEvent) {
    event.preventDefault();
    if (!file) return;
    const archiveBase64 = await fileBase64(file);
    submit("/api/packs/scan", { fileName: file.name, archiveBase64 });
  }
  return (
    <Card>
      <h2>Scan a candidate pack</h2>
      <p>The archive is inspected without activation or extraction into a live pack directory.</p>
      <form onSubmit={(event) => void scan(event)}>
        <label>
          MCpack or ZIP
          <input
            type="file"
            accept=".mcpack,.zip"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </label>
        <Button disabled={busy || !file}>Scan candidate</Button>
      </form>
    </Card>
  );
}

function NotificationForm({ busy, data, submit }: FormProps & { data: Record<string, unknown> }) {
  const publicKey = typeof data.vapidPublicKey === "string" ? data.vapidPublicKey : "";
  const [subscriptionId, setSubscriptionId] = useState("");
  const [revision, setRevision] = useState("1");
  async function subscribe() {
    if (!publicKey || !("serviceWorker" in navigator) || !("PushManager" in window)) return;
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: base64UrlBytes(publicKey),
    });
    const json = subscription.toJSON();
    submit("/api/notifications/subscriptions", {
      subscription: JSON.stringify({
        deviceName: navigator.userAgent.includes("iPhone") ? "iPhone" : "Browser",
        endpoint: json.endpoint ?? "",
        p256dh: json.keys?.p256dh ?? "",
        auth: json.keys?.auth ?? "",
        topics: [
          "BACKEND_UNHEALTHY",
          "DRAIN_FAILED",
          "HIGH_PRIORITY_SUPPORT_TICKET",
          "PACK_SCAN_FAILED",
        ],
      }),
    });
  }
  return (
    <Card>
      <h2>Mobile notifications</h2>
      <p>
        Subscriptions are device-named and tenant-scoped. Payloads contain only a short summary and
        dashboard route.
      </p>
      <Button disabled={busy || !publicKey} onClick={() => void subscribe()}>
        Subscribe this device
      </Button>
      <Button
        className="secondary"
        disabled={busy}
        onClick={() => submit("/api/notifications/test", {})}
      >
        Queue test notification
      </Button>
      <div className="inlineForm">
        <label>
          Subscription ID to revoke
          <input
            value={subscriptionId}
            onChange={(event) => setSubscriptionId(event.target.value)}
          />
        </label>
        <label>
          Revision
          <input value={revision} onChange={(event) => setRevision(event.target.value)} />
        </label>
        <Button
          className="secondary"
          disabled={busy || !subscriptionId}
          onClick={() =>
            submit(
              "/api/notifications/subscriptions",
              { subscriptionId, revision: Number(revision) },
              "DELETE",
            )
          }
        >
          Revoke device
        </Button>
      </div>
      {!publicKey ? (
        <small className="fieldHelp">
          Configure notifications.vapidPublicKey before subscribing.
        </small>
      ) : null}
    </Card>
  );
}

function FlowOperationsForm({ busy, submit }: FormProps) {
  const [workflowId, setWorkflowId] = useState("");
  const [executionId, setExecutionId] = useState("");
  return (
    <Card>
      <h2>Run and approve workflows</h2>
      <div className="formGrid">
        <label>
          Workflow ID
          <input value={workflowId} onChange={(event) => setWorkflowId(event.target.value)} />
        </label>
        <label>
          Execution ID
          <input value={executionId} onChange={(event) => setExecutionId(event.target.value)} />
        </label>
      </div>
      <Button
        disabled={busy || !workflowId}
        onClick={() => submit(`/api/flow/workflows/${encodeURIComponent(workflowId)}/run`, {})}
      >
        Run now
      </Button>
      <Button
        className="secondary"
        disabled={busy || !workflowId}
        onClick={() => submit(`/api/flow/workflows/${encodeURIComponent(workflowId)}/dry-run`, {})}
      >
        Dry-run preview
      </Button>
      <Button
        disabled={busy || !executionId}
        onClick={() =>
          submit(`/api/flow/executions/${encodeURIComponent(executionId)}/approve`, {})
        }
      >
        Approve waiting execution
      </Button>
      <Button
        className="secondary"
        disabled={busy || !executionId}
        onClick={() => submit(`/api/flow/executions/${encodeURIComponent(executionId)}/cancel`, {})}
      >
        Cancel execution
      </Button>
    </Card>
  );
}

interface FormProps {
  busy: boolean;
  submit: (
    path: string,
    body: Record<string, string | number | boolean>,
    method?: "POST" | "PUT" | "DELETE",
  ) => void;
}

function friendly(value: string) {
  return value.replace(/([A-Z])/g, " $1").replace(/^./, (character) => character.toUpperCase());
}

function fileBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error("Unable to read file"));
    reader.onload = () => {
      if (typeof reader.result !== "string") {
        reject(new Error("Unable to encode file"));
        return;
      }
      resolve(reader.result.split(",", 2)[1] ?? "");
    };
    reader.readAsDataURL(file);
  });
}

function base64UrlBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = `${value.replace(/-/g, "+").replace(/_/g, "/")}${"=".repeat((4 - (value.length % 4)) % 4)}`;
  const raw = atob(padded);
  return Uint8Array.from(raw, (character) => character.charCodeAt(0));
}

async function loadModuleHealth(signal: AbortSignal): Promise<ModulesResponse> {
  const cacheName = "onilink-health-v1";
  const cacheKey = "/offline-module-health.json";
  try {
    const live = await dashboardApi.platformGet<ModulesResponse>("/api/modules", {}, signal);
    if ("caches" in window) {
      const safe: ModulesResponse = { modules: live.modules, eventBus: live.eventBus, actions: [] };
      const cache = await caches.open(cacheName);
      await cache.put(
        cacheKey,
        new Response(JSON.stringify(safe), { headers: { "Content-Type": "application/json" } }),
      );
    }
    return live;
  } catch (failure) {
    if ("caches" in window) {
      const cached = await caches.match(cacheKey);
      if (cached) {
        const value = (await cached.json()) as ModulesResponse;
        return { ...value, cachedAt: new Date().toLocaleString() };
      }
    }
    throw failure;
  }
}
