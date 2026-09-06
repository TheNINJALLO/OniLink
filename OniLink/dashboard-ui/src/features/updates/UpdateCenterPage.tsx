import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { request, downloadText } from "../../api/client";
import { Button, Card, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { messageOf } from "../../utilities/format";
import "./updates.css";

type RecordValue = Record<string, unknown>;
const displayText = (value: unknown): string =>
  typeof value === "string" || typeof value === "number" || typeof value === "boolean"
    ? String(value)
    : "";
type Section = "updates" | "protocols" | "packs" | "players" | "cluster" | "metrics";
type Snapshot = Record<Section, RecordValue> & {
  restoreError: string;
  continuityEnabled: boolean;
  playerServicesEnabled: boolean;
};
type Field = { key: string; label: string; type?: "number" | "json" | "text"; initial?: string };
type Action = {
  id: string;
  label: string;
  section: Section;
  fields: Field[];
  confirmation?: string;
};
const field = (key: string, label: string, type: Field["type"] = "text", initial = ""): Field => ({
  key,
  label,
  type,
  initial,
});
const artifact = field("artifact", "Server artifact SHA-256");
const backend = field("backend", "Backend name");
const revision = field("revision", "Current revision", "number", "0");
const actions: Action[] = [
  {
    id: "update.preview",
    label: "Review candidate",
    section: "updates",
    fields: [artifact, backend],
  },
  {
    id: "update.lab",
    label: "Run compatibility fixtures",
    section: "updates",
    fields: [artifact, field("fixtures", "Fixture archive SHA-256")],
  },
  {
    id: "update.accept",
    label: "Record live acceptance",
    section: "updates",
    fields: [
      artifact,
      field("endstoneVersion", "Tested Endstone version"),
      field("evidence", "Evidence location and results"),
    ],
  },
  {
    id: "update.deploy",
    label: "Deploy reviewed candidate",
    section: "updates",
    fields: [artifact, backend],
    confirmation:
      "I reviewed this exact candidate and its acceptance evidence. Evacuate, back up and update this backend.",
  },
  {
    id: "update.rollback",
    label: "Restore server snapshot",
    section: "updates",
    fields: [field("job", "Maintenance job ID"), revision],
    confirmation:
      "Restore this job's verified snapshot. The backend must be empty before restoration.",
  },
  {
    id: "update.recover",
    label: "Resume after manual repair",
    section: "updates",
    fields: [
      field("job", "Maintenance job ID"),
      revision,
      field("version", "Recovered Bedrock version"),
      field("endstoneVersion", "Tested Endstone version"),
      field("evidence", "Manual repair and live acceptance evidence"),
    ],
    confirmation:
      "I repaired and tested this exact Endstone server. Check its health and version, then return reserved players.",
  },
  {
    id: "protocol.trust",
    label: "Verify package signature",
    section: "protocols",
    fields: [
      field("artifact", "Protocol archive SHA-256"),
      field("publisher", "Trusted publisher ID"),
      field("signature", "Detached Ed25519 signature (Base64)"),
    ],
  },
  {
    id: "protocol.activate",
    label: "Activate protocol package set",
    section: "protocols",
    fields: [field("artifacts", "Ordered package SHA-256 list (JSON)", "json", "[]"), revision],
    confirmation:
      "Load this trusted package set after its fixtures pass. Existing sessions keep their protocol snapshot. An empty set restores the built-in protocols.",
  },
  {
    id: "packs.stage",
    label: "Validate pack release",
    section: "packs",
    fields: [
      field("name", "Release name"),
      field("artifacts", "Resource-pack SHA-256 list (JSON)", "json", "[]"),
    ],
  },
  {
    id: "packs.activate",
    label: "Activate or restore pack release",
    section: "packs",
    fields: [field("release", "Validated release ID"), revision],
    confirmation:
      "Use this exact pack release for new connections. Existing players must reconnect to receive changed packs.",
  },
  {
    id: "players.admission",
    label: "Configure transfer queue",
    section: "players",
    fields: [
      backend,
      field("capacity", "Backend capacity", "number", "100"),
      field("reservedSlots", "Reserved slots", "number", "0"),
      field("reservedXuids", "Reserved player XUIDs (JSON)", "json", "[]"),
      revision,
    ],
  },
  {
    id: "cluster.policy",
    label: "Publish network policy",
    section: "cluster",
    fields: [
      field("kind", "Policy type: moderation, routing or quota"),
      field("target", "Player XUID or backend name"),
      field("value", "Policy settings (JSON)", "json", "{}"),
      revision,
    ],
    confirmation: "Publish this policy from the network authority to the configured nodes.",
  },
];
const checks = [
  "startup",
  "pluginLoaded",
  "clientJoin",
  "movement",
  "inventory",
  "crafting",
  "commands",
  "packs",
  "transfers",
  "shutdown",
];
const checkLabels: Record<string, string> = {
  startup: "Endstone started",
  pluginLoaded: "OniBridge loaded",
  clientJoin: "Real client joined",
  movement: "Movement",
  inventory: "Inventory",
  crafting: "Crafting",
  commands: "Commands",
  packs: "Resource packs",
  transfers: "Backend transfers",
  shutdown: "Clean shutdown",
};
const sections: Array<[Section, string]> = [
  ["updates", "Server updates"],
  ["protocols", "Protocol packages"],
  ["packs", "Pack releases"],
  ["players", "Player network"],
  ["cluster", "Proxy nodes"],
  ["metrics", "OniPulse"],
];

function records(value: unknown): RecordValue[] {
  return Array.isArray(value)
    ? value.filter(
        (item): item is RecordValue =>
          item !== null && typeof item === "object" && !Array.isArray(item),
      )
    : [];
}

export function UpdateCenterPage() {
  const queryClient = useQueryClient();
  const [tenant, setTenant] = useState("provider");
  const [proxy, setProxy] = useState("main");
  const [section, setSection] = useState<Section>("updates");
  const [selectedAction, setSelectedAction] = useState("update.preview");
  const [values, setValues] = useState<Record<string, string>>({});
  const [verified, setVerified] = useState<Record<string, boolean>>({});
  const [confirmed, setConfirmed] = useState(false);
  const [file, setFile] = useState<File>();
  const [kind, setKind] = useState("server");
  const [version, setVersion] = useState("1.26.45.1");
  const [platform, setPlatform] = useState("windows");
  const [requestId, setRequestId] = useState(() => crypto.randomUUID());
  const [review, setReview] = useState<RecordValue>();
  const [result, setResult] = useState<RecordValue>();
  const scope = new URLSearchParams({ tenant, proxy }).toString();
  const status = useQuery({
    queryKey: ["update-center", tenant, proxy],
    queryFn: ({ signal }) => request<Snapshot>(`/api/update-center?${scope}`, { signal }),
    refetchInterval: 5000,
    enabled: Boolean(tenant.trim() && proxy.trim()),
  });
  const action = actions.find((item) => item.id === selectedAction)!;
  function reset() {
    setConfirmed(false);
    setVerified({});
    setReview(undefined);
    setRequestId(crypto.randomUUID());
  }
  const execute = useMutation({
    mutationFn: async () => {
      const input: RecordValue = {};
      for (const item of action.fields) {
        const value = values[item.key] ?? item.initial ?? "";
        if (!value.trim()) throw new Error(`${item.label} is required.`);
        input[item.key] =
          item.type === "json"
            ? JSON.parse(value)
            : item.type === "number"
              ? Number(value)
              : value.trim();
        if (item.type === "number" && !Number.isSafeInteger(input[item.key]))
          throw new Error(`${item.label} must be a whole number.`);
      }
      if (action.id === "update.accept") Object.assign(input, verified);
      if (action.id === "update.deploy") input.requestId = requestId;
      return request<RecordValue>("/api/update-center/action", {
        method: "POST",
        body: { tenant, proxy, operation: action.id, input: JSON.stringify(input), confirmed },
      });
    },
    onSuccess: (response) => {
      setResult(response);
      if (action.id === "update.preview") setReview(response);
      setConfirmed(false);
      void queryClient.invalidateQueries({ queryKey: ["update-center", tenant, proxy] });
    },
  });
  const upload = useMutation({
    mutationFn: () => {
      if (!file) throw new Error("Select an archive first.");
      if (file.size > 536870912) throw new Error("The dashboard upload limit is 512 MiB.");
      const query = new URLSearchParams({
        tenant,
        proxy,
        kind,
        version,
        platform: kind === "server" ? platform : "any",
      });
      return request<RecordValue>(`/api/update-center/upload?${query}`, {
        method: "POST",
        rawBody: file,
      });
    },
    onSuccess: (response) => {
      setResult(response);
      void queryClient.invalidateQueries({ queryKey: ["update-center", tenant, proxy] });
    },
  });
  const busy = execute.isPending || upload.isPending;
  const ready =
    action.id !== "update.deploy" ||
    (review?.ready === true &&
      review.backend === values.backend &&
      (review.artifact as RecordValue | undefined)?.id === values.artifact);
  const data = status.data?.[section] ?? {};
  return (
    <>
      <PageHeader
        title="Update Center"
        description="Stage and verify releases, manage Endstone maintenance, and operate the player network."
      />
      <Card>
        <div className="updateScope">
          <label>
            Tenant
            <input
              value={tenant}
              disabled={busy}
              onChange={(event) => {
                setTenant(event.target.value);
                reset();
                setResult(undefined);
              }}
            />
          </label>
          <label>
            Proxy
            <input
              value={proxy}
              disabled={busy}
              onChange={(event) => {
                setProxy(event.target.value);
                reset();
                setResult(undefined);
              }}
            />
          </label>
          <Status state="neutral">Native runtime: Endstone</Status>
        </div>
      </Card>
      <nav className="updateTabs" aria-label="Update Center sections">
        {sections.map(([id, label]) => (
          <Button
            key={id}
            aria-pressed={section === id}
            disabled={busy}
            onClick={() => {
              setSection(id);
              const next = actions.find((item) => item.section === id);
              if (next) setSelectedAction(next.id);
              setConfirmed(false);
            }}
          >
            {label}
          </Button>
        ))}
      </nav>
      <Notice
        error
        message={status.error ? messageOf(status.error) : (status.data?.restoreError ?? "")}
      />
      {status.isPending && <Loading label="Loading operational state" />}
      {section !== "metrics" && (
        <div className="updateGrid">
          {(section === "updates" || section === "protocols" || section === "packs") && (
            <Card>
              <h2>Stage an archive</h2>
              <p>
                Uploads stay in the artifact store until a separate activation. ZIP contents and
                executable identity are checked without starting the server.
              </p>
              <form
                className="updateForm"
                onSubmit={(event) => {
                  event.preventDefault();
                  upload.mutate();
                }}
              >
                <label>
                  Archive type
                  <select
                    value={kind}
                    disabled={busy}
                    onChange={(event) => setKind(event.target.value)}
                  >
                    <option value="server">Bedrock server files</option>
                    <option value="protocol">Signed protocol JAR</option>
                    <option value="pack">Resource pack ZIP or MCPACK</option>
                    <option value="fixtures">Sanitized compatibility fixtures</option>
                  </select>
                </label>
                <label>
                  Release version
                  <input
                    required
                    value={version}
                    disabled={busy}
                    onChange={(event) => setVersion(event.target.value)}
                  />
                </label>
                {kind === "server" && (
                  <label>
                    Operating system
                    <select
                      value={platform}
                      disabled={busy}
                      onChange={(event) => setPlatform(event.target.value)}
                    >
                      <option value="windows">Windows</option>
                      <option value="linux">Linux</option>
                    </select>
                  </label>
                )}
                <label>
                  Archive file
                  <input
                    required
                    type="file"
                    accept=".zip,.jar,.mcpack"
                    disabled={busy}
                    onChange={(event) => setFile(event.target.files?.[0])}
                  />
                </label>
                <Button disabled={busy || !file}>
                  {upload.isPending ? "Uploading and checking…" : "Upload and verify"}
                </Button>
                <Notice error message={upload.error ? messageOf(upload.error) : ""} />
              </form>
            </Card>
          )}
          <Card>
            <h2>Release actions</h2>
            <form
              className="updateForm"
              onSubmit={(event) => {
                event.preventDefault();
                execute.mutate();
              }}
            >
              <label>
                Action
                <select
                  value={selectedAction}
                  disabled={busy}
                  onChange={(event) => {
                    setSelectedAction(event.target.value);
                    setConfirmed(false);
                    execute.reset();
                  }}
                >
                  {actions
                    .filter((item) => item.section === section)
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.label}
                      </option>
                    ))}
                </select>
              </label>
              {action.fields.map((item) => (
                <label key={item.key}>
                  {item.label}
                  {item.type === "json" || item.key === "evidence" ? (
                    <textarea
                      rows={3}
                      required
                      value={values[item.key] ?? item.initial ?? ""}
                      disabled={busy}
                      onChange={(event) => {
                        setValues({ ...values, [item.key]: event.target.value });
                        reset();
                      }}
                    />
                  ) : (
                    <input
                      required
                      type={item.type === "number" ? "number" : "text"}
                      value={values[item.key] ?? item.initial ?? ""}
                      disabled={busy}
                      onChange={(event) => {
                        setValues({ ...values, [item.key]: event.target.value });
                        reset();
                      }}
                    />
                  )}
                </label>
              ))}
              {action.id === "update.accept" && (
                <fieldset>
                  <legend>Checks completed against this exact archive and Endstone version</legend>
                  {checks.map((check) => (
                    <label className="updateCheck" key={check}>
                      <input
                        type="checkbox"
                        checked={verified[check] ?? false}
                        disabled={busy}
                        onChange={(event) =>
                          setVerified({ ...verified, [check]: event.target.checked })
                        }
                      />
                      {checkLabels[check]}
                    </label>
                  ))}
                </fieldset>
              )}
              {!ready && (
                <p>
                  Run “Review candidate” with this artifact and backend first. All blockers must be
                  resolved.
                </p>
              )}
              {action.confirmation && (
                <label className="updateCheck">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    disabled={busy}
                    onChange={(event) => setConfirmed(event.target.checked)}
                  />
                  {action.confirmation}
                </label>
              )}
              <Button
                disabled={
                  busy ||
                  !ready ||
                  Boolean(action.confirmation && !confirmed) ||
                  (action.id === "update.accept" && checks.some((check) => !verified[check]))
                }
              >
                {execute.isPending ? "Working…" : action.label}
              </Button>
              <Notice error message={execute.error ? messageOf(execute.error) : ""} />
            </form>
          </Card>
        </div>
      )}
      {section === "players" && (
        <Card>
          <h2>In-game commands</h2>
          <p>
            <code>/network party</code>, <code>/network friend</code>, <code>/network chat</code>,
            and <code>/network servers</code> are available when Connect is enabled. Transfer queues
            reserve space for the whole group and confirm arrivals. Direct initial joins and forced
            staff moves also consume backend capacity; configure the backend limit accordingly.
          </p>
          <Status state={status.data?.playerServicesEnabled ? "ok" : "warning"}>
            {status.data?.playerServicesEnabled
              ? "Player services enabled"
              : "Enable Connect in Platform to use player services"}
          </Status>
        </Card>
      )}
      {section === "cluster" && (
        <Card>
          <h2>Network authority</h2>
          <p>
            Configure node keys and allowed tenant/proxy scopes on the server. Shared quotas require
            an authority policy for each backend. Players reconnect through another node if a proxy
            fails.
          </p>
          <p>
            Policy examples: <code>{'{"banned":true,"muted":true}'}</code>,{" "}
            <code>{'{"enabled":false}'}</code>, <code>{'{"capacity":100}'}</code>.
          </p>
        </Card>
      )}
      {result && (
        <Card>
          <h2>Latest result</h2>
          <Button
            onClick={() => downloadText("onilink-operation.json", JSON.stringify(result, null, 2))}
          >
            Download evidence
          </Button>
          {Array.isArray(result.blockers) && (
            <ul>
              {result.blockers.map((blocker) => (
                <li key={String(blocker)}>{String(blocker)}</li>
              ))}
            </ul>
          )}
          <pre className="updateEvidence">{JSON.stringify(result, null, 2)}</pre>
        </Card>
      )}
      <Card>
        <h2>{sections.find(([id]) => id === section)?.[1]} status</h2>
        {Object.entries(data).map(([key, value]) => (
          <details key={key} open={key === "jobs" || key === "active" || key === "relayRoutes"}>
            <summary>
              {key.replace(/([A-Z])/g, " $1")} {Array.isArray(value) ? `(${value.length})` : ""}
            </summary>
            {records(value).length > 0 ? (
              <div className="updateTable">
                <table>
                  <thead>
                    <tr>
                      <th>Item</th>
                      <th>State / version</th>
                      <th>Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {records(value).map((item, index) => (
                      <tr key={displayText(item.id ?? item.route) || index}>
                        <td>
                          <code>
                            {displayText(item.name ?? item.backend ?? item.route ?? item.id) ||
                              index}
                          </code>
                        </td>
                        <td>{displayText(item.state ?? item.status ?? item.version)}</td>
                        <td>
                          <details>
                            <summary>View record</summary>
                            <pre className="updateEvidence">{JSON.stringify(item, null, 2)}</pre>
                          </details>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <pre className="updateEvidence">{JSON.stringify(value, null, 2)}</pre>
            )}
          </details>
        ))}
      </Card>
    </>
  );
}
