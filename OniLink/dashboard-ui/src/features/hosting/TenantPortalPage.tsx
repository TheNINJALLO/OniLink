import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Pause, Play, Plus, RefreshCw, RotateCw, Send, Trash2 } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { downloadBase64 } from "../../api/client";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Card, Empty, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import type { AllowlistEntry, BackendSetup, Player } from "../../types/dashboard";
import { duration, messageOf } from "../../utilities/format";

type Selection = { tenant: string; proxy: string };
type RuntimeSelection = { player: Player; action: "transfer" | "disconnect" | "trace" };

function RuntimeDialog({
  selection,
  selected,
  backends,
  close,
  complete,
}: {
  selection: Selection;
  selected: RuntimeSelection;
  backends: string[];
  close: () => void;
  complete: (message: string) => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [backend, setBackend] = useState(backends[0] ?? "");
  const [reason, setReason] = useState("Disconnected by an operator");
  const mutation = useMutation({
    mutationFn: () =>
      dashboardApi.tenantRuntimeAction({
        ...selection,
        action: selected.action,
        player: selected.player.name,
        ...(selected.action === "transfer" ? { backend } : {}),
        ...(selected.action === "disconnect" ? { reason } : {}),
        ...(selected.action === "trace" ? { milliseconds: 10000 } : {}),
      }),
    onSuccess: (result) => complete(result.message),
  });
  useEffect(() => {
    ref.current?.showModal();
  }, []);
  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby="tenant-player-action"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onClose={close}
    >
      <h2 id="tenant-player-action">
        Confirm {selected.action} · {selected.player.name}
      </h2>
      <p>The selected live proxy will execute this operation only after confirmation.</p>
      {selected.action === "transfer" ? (
        <label>
          Destination backend
          <select value={backend} onChange={(event) => setBackend(event.target.value)}>
            {backends.map((name) => (
              <option key={name}>{name}</option>
            ))}
          </select>
        </label>
      ) : null}
      {selected.action === "disconnect" ? (
        <label>
          Disconnect reason
          <input value={reason} onChange={(event) => setReason(event.target.value)} />
        </label>
      ) : null}
      <Notice message={mutation.error ? messageOf(mutation.error) : ""} error />
      <div className="dialogActions">
        <Button className="secondary" onClick={close}>
          Cancel
        </Button>
        <Button
          className={selected.action === "disconnect" ? "danger" : ""}
          disabled={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {mutation.isPending ? "Submitting…" : `Confirm ${selected.action}`}
        </Button>
      </div>
    </dialog>
  );
}

export function TenantPortalPage() {
  const { principal } = useAuth();
  const client = useQueryClient();
  const tenancy = useQuery({
    queryKey: ["tenancy"],
    queryFn: ({ signal }) => dashboardApi.tenancy(signal),
  });
  const available = useMemo(() => tenancy.data?.proxies ?? [], [tenancy.data]);
  const defaultKey = sessionStorage.getItem("onilink:selected-proxy") ?? "";
  const [key, setKey] = useState(defaultKey);
  const activeKey = key || (available[0] ? `${available[0].tenantId}/${available[0].id}` : "");
  const selectedProxy =
    available.find((proxy) => `${proxy.tenantId}/${proxy.id}` === activeKey) ?? available[0];
  const selection = selectedProxy
    ? { tenant: selectedProxy.tenantId, proxy: selectedProxy.id }
    : null;
  const proxy = useQuery({
    queryKey: ["tenant-proxy", selection?.tenant, selection?.proxy],
    queryFn: ({ signal }) => dashboardApi.tenantProxy(selection!.tenant, selection!.proxy, signal),
    enabled: Boolean(selection),
    refetchInterval: 5_000,
  });
  const [message, setMessage] = useState("");
  const [allow, setAllow] = useState({ xuid: "", name: "" });
  const [remove, setRemove] = useState<AllowlistEntry | null>(null);
  const [alert, setAlert] = useState("");
  const [backend, setBackend] = useState({ name: "", address: "", proxyPublicIp: "" });
  const [backendResult, setBackendResult] = useState<BackendSetup | null>(null);
  const [runtime, setRuntime] = useState<RuntimeSelection | null>(null);
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: ["tenant-proxy"] });
    await client.invalidateQueries({ queryKey: ["tenancy"] });
  };
  const lifecycle = useMutation({
    mutationFn: (action: string) =>
      dashboardApi.tenantProxyAction(selection!.tenant, selection!.proxy, action),
    onSuccess: async (result) => {
      setMessage(result.message);
      await refresh();
    },
  });
  const allowAdd = useMutation({
    mutationFn: () => dashboardApi.addTenantAllowlist({ ...selection!, ...allow }),
    onSuccess: async (result) => {
      setMessage(result.message);
      setAllow({ xuid: "", name: "" });
      await refresh();
    },
  });
  const allowDrop = useMutation({
    mutationFn: () =>
      dashboardApi.removeTenantAllowlist({ ...selection!, xuid: remove?.xuid ?? "" }),
    onSuccess: async (result) => {
      setMessage(result.message);
      setRemove(null);
      await refresh();
    },
  });
  const sendAlert = useMutation({
    mutationFn: () =>
      dashboardApi.tenantRuntimeAction({ ...selection!, action: "alert", message: alert }),
    onSuccess: (result) => {
      setMessage(result.message);
      setAlert("");
    },
  });
  const addBackend = useMutation({
    mutationFn: () =>
      dashboardApi.addTenantBackend({
        ...selection!,
        ...backend,
        revision: proxy.data?.configurationRevision ?? "",
      }),
    onSuccess: async (result) => {
      setBackendResult(result);
      setBackend({ name: "", address: "", proxyPublicIp: "" });
      await refresh();
    },
  });
  const activeError =
    tenancy.error ??
    proxy.error ??
    lifecycle.error ??
    allowAdd.error ??
    allowDrop.error ??
    sendAlert.error ??
    addBackend.error;
  const state = proxy.data;
  const backendNames = useMemo(() => (state?.backends ?? []).map((item) => item.name), [state]);
  return (
    <>
      <PageHeader
        title={principal?.role === "tenant" ? "My Proxies" : "Tenant Proxy"}
        description="Operate one isolated proxy and its permitted routing resources."
        actions={
          <Button className="secondary" onClick={() => void refresh()}>
            <RefreshCw aria-hidden="true" />
            Refresh
          </Button>
        }
      />
      <Notice message={message} />
      <Notice message={activeError ? messageOf(activeError) : ""} error />
      {available.length ? (
        <label className="proxySelector">
          Proxy
          <select
            value={activeKey}
            onChange={(event) => {
              setKey(event.target.value);
              sessionStorage.setItem("onilink:selected-proxy", event.target.value);
            }}
          >
            {available.map((item) => (
              <option key={`${item.tenantId}/${item.id}`} value={`${item.tenantId}/${item.id}`}>
                {item.label} · {item.publicAddress}:{item.port}
              </option>
            ))}
          </select>
        </label>
      ) : tenancy.isLoading ? (
        <Loading label="Loading assigned proxies" />
      ) : (
        <Empty
          title="No proxies assigned"
          detail="Ask the provider owner to allocate a proxy to this tenant."
        />
      )}
      {selectedProxy ? (
        <>
          <Card>
            <div className="sectionTitle">
              <div>
                <p className="eyebrow">Runtime</p>
                <h2>{selectedProxy.label}</h2>
              </div>
              <Status
                state={
                  selectedProxy.running
                    ? "ok"
                    : selectedProxy.status === "error"
                      ? "danger"
                      : "warning"
                }
              >
                {selectedProxy.status}
              </Status>
            </div>
            <dl className="detailList">
              <div>
                <dt>Player endpoint</dt>
                <dd>
                  {selectedProxy.publicAddress}:{selectedProxy.port}
                </dd>
              </div>
              <div>
                <dt>Players</dt>
                <dd>
                  {String(state?.state.players ?? 0)} / {selectedProxy.maxPlayers}
                </dd>
              </div>
              <div>
                <dt>MOTD</dt>
                <dd>{selectedProxy.motd}</dd>
              </div>
            </dl>
            <div className="buttonRow">
              <Button onClick={() => lifecycle.mutate(selectedProxy.running ? "restart" : "start")}>
                {selectedProxy.running ? (
                  <RotateCw aria-hidden="true" />
                ) : (
                  <Play aria-hidden="true" />
                )}
                {selectedProxy.running ? "Restart" : "Start"}
              </Button>
              {selectedProxy.running ? (
                <Button className="danger" onClick={() => lifecycle.mutate("stop")}>
                  <Pause aria-hidden="true" />
                  Stop
                </Button>
              ) : null}
              {selectedProxy.handoffAvailable ? (
                <Button
                  className="secondary"
                  onClick={() =>
                    void dashboardApi.tenantHandoff(selectedProxy.tenantId, selectedProxy.id)
                  }
                >
                  <Download aria-hidden="true" />
                  Download handoff
                </Button>
              ) : null}
            </div>
          </Card>
          <Card>
            <h2>Connected players</h2>
            {proxy.isLoading ? (
              <Loading label="Loading proxy runtime" />
            ) : state?.players.length ? (
              <div className="tableWrap">
                <table>
                  <thead>
                    <tr>
                      <th>Player</th>
                      <th>Route</th>
                      <th>Connected</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {state.players.map((player) => (
                      <tr key={player.xuid || player.name}>
                        <td>
                          <strong>{player.name}</strong>
                          <small>{player.identity}</small>
                        </td>
                        <td>{player.backend}</td>
                        <td>{duration(player.connectedMillis)}</td>
                        <td>
                          <div className="rowActions">
                            <Button
                              className="secondary compact"
                              onClick={() => setRuntime({ player, action: "transfer" })}
                            >
                              Transfer
                            </Button>
                            <Button
                              className="secondary compact"
                              onClick={() => setRuntime({ player, action: "trace" })}
                            >
                              Trace
                            </Button>
                            <Button
                              className="danger compact"
                              onClick={() => setRuntime({ player, action: "disconnect" })}
                            >
                              Disconnect
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty
                title="No connected players"
                detail="Players connected through this tenant proxy will appear here."
              />
            )}
          </Card>
          <div className="twoColumn">
            <Card>
              <h2>Proxy allowlist</h2>
              <p className="fieldHint">XUID is authoritative; the name is only a label.</p>
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  allowAdd.mutate();
                }}
              >
                <label>
                  XUID
                  <input
                    inputMode="numeric"
                    pattern="\d+"
                    required
                    value={allow.xuid}
                    onChange={(event) => setAllow({ ...allow, xuid: event.target.value })}
                  />
                </label>
                <label>
                  Gamertag label
                  <input
                    value={allow.name}
                    onChange={(event) => setAllow({ ...allow, name: event.target.value })}
                  />
                </label>
                <Button type="submit" disabled={allowAdd.isPending}>
                  <Plus aria-hidden="true" />
                  Add
                </Button>
              </form>
              <ul className="itemList">
                {state?.allowlist.entries.map((entry) => (
                  <li key={entry.xuid}>
                    <span>
                      <strong>{entry.name || "Unlabeled"}</strong>
                      <small>{entry.xuid}</small>
                    </span>
                    <Button className="danger compact" onClick={() => setRemove(entry)}>
                      <Trash2 aria-hidden="true" />
                      Remove
                    </Button>
                  </li>
                ))}
              </ul>
            </Card>
            <Card>
              <h2>Broadcast alert</h2>
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  sendAlert.mutate();
                }}
              >
                <label>
                  Message
                  <textarea
                    rows={4}
                    required
                    value={alert}
                    onChange={(event) => setAlert(event.target.value)}
                  />
                </label>
                <Button type="submit" disabled={sendAlert.isPending}>
                  <Send aria-hidden="true" />
                  Send alert
                </Button>
              </form>
            </Card>
          </div>
          <Card>
            <h2>Add backend route</h2>
            <p>Generate an isolated bridge key and installation bundle for this proxy.</p>
            {backendResult ? (
              <div className="resultCard">
                <div className="warningBox">
                  <strong>One-time secret result</strong>
                  <span>Download the private bundle now, then clear this result.</span>
                </div>
                <code className="secretValue">{backendResult.secret}</code>
                <div className="buttonRow">
                  <Button
                    onClick={() =>
                      downloadBase64(
                        backendResult.setupBundleFileName,
                        backendResult.setupBundleBase64,
                        "application/zip",
                      )
                    }
                  >
                    <Download aria-hidden="true" />
                    Download ZIP
                  </Button>
                  <Button className="danger" onClick={() => setBackendResult(null)}>
                    Clear result
                  </Button>
                </div>
              </div>
            ) : (
              <form
                className="inlineForm"
                onSubmit={(event) => {
                  event.preventDefault();
                  addBackend.mutate();
                }}
              >
                <label>
                  Route name
                  <input
                    pattern="[a-z][a-z0-9_-]{0,31}"
                    required
                    value={backend.name}
                    onChange={(event) => setBackend({ ...backend, name: event.target.value })}
                  />
                </label>
                <label>
                  BDS endpoint
                  <input
                    required
                    value={backend.address}
                    onChange={(event) => setBackend({ ...backend, address: event.target.value })}
                  />
                </label>
                <label>
                  Proxy source IP
                  <input
                    required
                    value={backend.proxyPublicIp}
                    onChange={(event) =>
                      setBackend({ ...backend, proxyPublicIp: event.target.value })
                    }
                  />
                </label>
                <Button type="submit" disabled={addBackend.isPending}>
                  {addBackend.isPending ? "Generating…" : "Generate backend setup"}
                </Button>
              </form>
            )}
          </Card>
        </>
      ) : null}
      <ConfirmDialog
        open={Boolean(remove)}
        title="Remove tenant allowlist entry?"
        description={`XUID ${remove?.xuid ?? ""} will no longer be authorized for this proxy.`}
        confirmLabel="Remove entry"
        destructive
        busy={allowDrop.isPending}
        onClose={() => setRemove(null)}
        onConfirm={() => allowDrop.mutate()}
      />
      {selection && runtime ? (
        <RuntimeDialog
          selection={selection}
          selected={runtime}
          backends={backendNames}
          close={() => setRuntime(null)}
          complete={(result) => {
            setRuntime(null);
            setMessage(result);
            void refresh();
          }}
        />
      ) : null}
    </>
  );
}
