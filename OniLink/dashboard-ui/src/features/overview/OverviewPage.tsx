import { useQuery } from "@tanstack/react-query";
import { Activity, Cpu, Gauge, MemoryStick, Network, Radio, UsersRound } from "lucide-react";
import { dashboardApi } from "../../api/dashboard";
import { Card, Loading, PageHeader, Status } from "../../components/ui";
import { bytes, duration } from "../../utilities/format";

export function OverviewPage() {
  const state = useQuery({
    queryKey: ["state"],
    queryFn: ({ signal }) => dashboardApi.state(signal),
    refetchInterval: 5_000,
  });
  const backends = useQuery({
    queryKey: ["backends"],
    queryFn: ({ signal }) => dashboardApi.backends(signal),
    refetchInterval: 10_000,
  });
  if (!state.data)
    return (
      <>
        <PageHeader title="Overview" description="Live status for this OniLink process." />
        <Loading label="Loading runtime" />
      </>
    );
  const healthy =
    backends.data?.backends.filter((backend) => backend.health.status === "online").length ?? 0;
  const degraded =
    backends.data?.backends.filter((backend) => backend.health.status === "degraded").length ?? 0;
  const offline =
    backends.data?.backends.filter((backend) => backend.health.status === "offline").length ?? 0;
  const metrics = [
    {
      label: "Players",
      value: `${state.data.players} / ${state.data.maxPlayers}`,
      icon: UsersRound,
    },
    { label: "Backends", value: String(state.data.backends), icon: Network },
    { label: "Uptime", value: duration(state.data.uptimeMillis), icon: Activity },
    {
      label: "Memory",
      value: `${bytes(state.data.memoryUsedBytes)} / ${bytes(state.data.memoryMaxBytes)}`,
      icon: MemoryStick,
    },
    {
      label: "System load",
      value:
        state.data.systemLoadAverage < 0 ? "Unavailable" : state.data.systemLoadAverage.toFixed(2),
      icon: Gauge,
    },
    { label: "Threads", value: String(state.data.threads), icon: Cpu },
  ];
  return (
    <>
      <PageHeader
        title="Overview"
        description="Live operational state from the embedded proxy runtime."
        actions={<Status state="ok">Online · {state.data.version}</Status>}
      />
      <div className="metricGrid">
        {metrics.map((item) => (
          <Card className="metric" key={item.label}>
            <item.icon aria-hidden="true" />
            <span>{item.label}</span>
            <strong>{item.value}</strong>
          </Card>
        ))}
      </div>
      <div className="twoColumn">
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Routing fabric</p>
              <h2>Backend health</h2>
            </div>
            <Radio aria-hidden="true" />
          </div>
          <div className="healthSummary">
            <div>
              <Status state="ok">Healthy</Status>
              <strong>{healthy}</strong>
            </div>
            <div>
              <Status state="warning">Degraded</Status>
              <strong>{degraded}</strong>
            </div>
            <div>
              <Status state="danger">Offline</Status>
              <strong>{offline}</strong>
            </div>
          </div>
          {backends.isError ? (
            <p className="errorText">Backend health could not be refreshed.</p>
          ) : null}
        </Card>
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Listener</p>
              <h2>Network entry point</h2>
            </div>
          </div>
          <dl className="detailList">
            <div>
              <dt>Bound address</dt>
              <dd>
                {state.data.listener.host}:{state.data.listener.port}
              </dd>
            </div>
            <div>
              <dt>Allowlist</dt>
              <dd>
                {state.data.allowlistEnabled
                  ? `${state.data.allowlistEntries} entries`
                  : "Not enforced"}
              </dd>
            </div>
            <div>
              <dt>Processors</dt>
              <dd>{state.data.processors}</dd>
            </div>
            <div>
              <dt>Committed memory</dt>
              <dd>{bytes(state.data.memoryCommittedBytes)}</dd>
            </div>
          </dl>
        </Card>
      </div>
    </>
  );
}
