import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, RefreshCw, Save, Star, Trash2, X } from "lucide-react";
import { useMemo, useState } from "react";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Card, Empty, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { hasRole } from "../../permissions/roles";
import type { Backend, ConfiguredBackend } from "../../types/dashboard";
import { messageOf } from "../../utilities/format";

type EditState = { name: string; host: string; port: string };

export function BackendsPage({ navigate }: { navigate: (route: string) => void }) {
  const { principal } = useAuth();
  const client = useQueryClient();
  const canManage = principal ? hasRole(principal.role, "admin") : false;
  const runtime = useQuery({
    queryKey: ["backends"],
    queryFn: ({ signal }) => dashboardApi.backends(signal),
    refetchInterval: 10_000,
  });
  const routing = useQuery({
    queryKey: ["backend-routing"],
    queryFn: ({ signal }) => dashboardApi.backendRouting(signal),
    enabled: canManage,
  });
  const [message, setMessage] = useState("");
  const [edit, setEdit] = useState<EditState | null>(null);
  const [removal, setRemoval] = useState<ConfiguredBackend | null>(null);
  const [replacement, setReplacement] = useState("");
  const revision = routing.data?.configurationRevision ?? "";

  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["backends"] }),
      client.invalidateQueries({ queryKey: ["backend-routing"] }),
      client.invalidateQueries({ queryKey: ["config"] }),
    ]);
  };
  const update = useMutation({
    mutationFn: (value: EditState) =>
      dashboardApi.updateBackend({
        name: value.name,
        host: value.host,
        port: value.port,
        revision,
      }),
    onSuccess: async (result) => {
      setMessage(`${result.message} Restart OniLink to apply the saved routing change.`);
      setEdit(null);
      await refresh();
    },
  });
  const setPrimary = useMutation({
    mutationFn: (backend: string) => dashboardApi.setPrimaryBackend({ backend, revision }),
    onSuccess: async (result) => {
      setMessage(`${result.message} Restart OniLink to send new players there.`);
      await refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (backend: string) =>
      dashboardApi.removeBackend({
        name: backend,
        replacementBackend: replacement,
        revision,
      }),
    onSuccess: async (result) => {
      setMessage(`${result.message} Restart OniLink to apply the saved routing change.`);
      setRemoval(null);
      setReplacement("");
      await refresh();
    },
  });
  const error = runtime.error ?? routing.error ?? update.error ?? setPrimary.error ?? remove.error;
  const runtimeByName = useMemo(
    () => new Map((runtime.data?.backends ?? []).map((backend) => [backend.name, backend])),
    [runtime.data],
  );
  const rows = useMemo<Backend[]>(() => {
    if (!canManage || !routing.data) return runtime.data?.backends ?? [];
    return routing.data.configuredBackends.map((configured) => {
      const active = runtimeByName.get(configured.name);
      return {
        name: configured.name,
        host: configured.host,
        port: configured.port,
        protocol: active?.protocol ?? "pending restart",
        players: active?.players ?? 0,
        default: configured.primary,
        hub: configured.hub,
        forwarding: active?.forwarding ?? true,
        health: active?.health ?? {
          status: "checking",
          latencyMillis: -1,
          message: "Loads after restart",
        },
      };
    });
  }, [canManage, routing.data, runtime.data, runtimeByName]);
  const configured = routing.data?.configuredBackends ?? [];

  const chooseRemoval = (backend: Backend) => {
    const route = configured.find((item) => item.name === backend.name);
    if (!route) return;
    setRemoval(route);
    setReplacement(configured.find((item) => item.name !== backend.name)?.name ?? "");
    setEdit(null);
  };

  return (
    <>
      <PageHeader
        title="Backends"
        description="Health, destinations, routing roles, and management for every Bedrock server."
        actions={
          <>
            <Button
              className="secondary"
              onClick={() => void refresh()}
              disabled={runtime.isFetching || routing.isFetching}
            >
              <RefreshCw aria-hidden="true" />
              Refresh
            </Button>
            {canManage ? (
              <Button onClick={() => navigate("add-backend")}>Add backend</Button>
            ) : null}
          </>
        }
      />
      <Notice message={message} />
      <Notice message={error ? messageOf(error) : ""} error />
      {runtime.isLoading || (canManage && routing.isLoading) ? (
        <Loading label="Checking backends" />
      ) : rows.length ? (
        <div className="tableWrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Health</th>
                {canManage ? <th>Destination server</th> : null}
                <th>Latency</th>
                <th>Population</th>
                <th>Routing</th>
                <th>Flags</th>
                {canManage ? <th>Actions</th> : null}
              </tr>
            </thead>
            <tbody>
              {rows.map((backend) => (
                <tr key={backend.name}>
                  <td>
                    <strong>{backend.name}</strong>
                    <small>{backend.protocol}</small>
                  </td>
                  <td>
                    <Status
                      state={
                        backend.health.status === "online"
                          ? "ok"
                          : backend.health.status === "degraded" ||
                              backend.health.status === "checking"
                            ? "warning"
                            : "danger"
                      }
                    >
                      {backend.health.status}
                    </Status>
                    {backend.health.message ? <small>{backend.health.message}</small> : null}
                  </td>
                  {canManage ? (
                    <td className="mono">
                      {backend.host}:{backend.port}
                    </td>
                  ) : null}
                  <td>
                    {backend.health.latencyMillis >= 0 ? `${backend.health.latencyMillis} ms` : "—"}
                  </td>
                  <td>{backend.players}</td>
                  <td>{backend.default ? "Primary" : backend.hub ? "Hub" : "Route"}</td>
                  <td>
                    <span className="tag">{backend.forwarding ? "Forwarded" : "Direct"}</span>
                  </td>
                  {canManage ? (
                    <td>
                      <div className="rowActions">
                        <Button
                          className="secondary compact"
                          disabled={backend.default || setPrimary.isPending || !revision}
                          onClick={() => {
                            if (
                              window.confirm(
                                `Make ${backend.name} the primary server for new connections?`,
                              )
                            )
                              setPrimary.mutate(backend.name);
                          }}
                        >
                          <Star aria-hidden="true" />
                          {backend.default ? "Primary" : "Set primary"}
                        </Button>
                        <Button
                          className="secondary compact"
                          onClick={() => {
                            setEdit({
                              name: backend.name,
                              host: backend.host,
                              port: String(backend.port),
                            });
                            setRemoval(null);
                          }}
                        >
                          <Pencil aria-hidden="true" />
                          Edit
                        </Button>
                        <Button
                          className="danger compact"
                          disabled={configured.length <= 1}
                          onClick={() => chooseRemoval(backend)}
                        >
                          <Trash2 aria-hidden="true" />
                          Remove
                        </Button>
                      </div>
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Card>
          <Empty
            title="No backends configured"
            detail="An administrator can create the first secured backend route."
          />
        </Card>
      )}

      {edit ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Edit destination</p>
              <h2>{edit.name}</h2>
            </div>
            <Button className="secondary compact" onClick={() => setEdit(null)}>
              <X aria-hidden="true" /> Close
            </Button>
          </div>
          <p>
            Change where this route forwards players. Its backend name, OniBridge identity, and
            forwarding key stay unchanged.
          </p>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              update.mutate(edit);
            }}
          >
            <div className="formGrid">
              <label>
                Destination server IP or domain
                <input
                  required
                  value={edit.host}
                  onChange={(event) => setEdit({ ...edit, host: event.target.value })}
                />
              </label>
              <label>
                Destination UDP port
                <input
                  type="number"
                  min="1"
                  max="65535"
                  required
                  value={edit.port}
                  onChange={(event) => setEdit({ ...edit, port: event.target.value })}
                />
              </label>
            </div>
            <Button disabled={update.isPending || !revision}>
              <Save aria-hidden="true" />
              {update.isPending ? "Saving…" : "Save destination"}
            </Button>
          </form>
        </Card>
      ) : null}

      {removal ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Remove route</p>
              <h2>Remove {removal.name}?</h2>
            </div>
            <Button className="secondary compact" onClick={() => setRemoval(null)}>
              <X aria-hidden="true" /> Cancel
            </Button>
          </div>
          <p>
            The route will be removed and references will move to the replacement below. Key files
            are retained for recovery. Restarting OniLink disconnects current players.
          </p>
          <label>
            Replacement route
            <span className="fieldHelp">
              Used if this backend is primary, hub, failover, forced-host, limbo, or quarantine.
            </span>
            <select value={replacement} onChange={(event) => setReplacement(event.target.value)}>
              {configured
                .filter((item) => item.name !== removal.name)
                .map((item) => (
                  <option key={item.name} value={item.name}>
                    {item.name} · {item.address}
                  </option>
                ))}
            </select>
          </label>
          <Button
            className="danger"
            disabled={remove.isPending || !replacement || !revision}
            onClick={() => {
              if (
                window.confirm(`Permanently remove the ${removal.name} route from configuration?`)
              )
                remove.mutate(removal.name);
            }}
          >
            <Trash2 aria-hidden="true" />
            {remove.isPending ? "Removing…" : "Remove backend route"}
          </Button>
        </Card>
      ) : null}
    </>
  );
}
