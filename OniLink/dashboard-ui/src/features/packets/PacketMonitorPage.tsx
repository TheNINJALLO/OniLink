import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  Database,
  Download,
  Pause,
  Play,
  RefreshCw,
  Route,
  Search,
  ShieldCheck,
  TriangleAlert,
  Workflow,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Card, Empty, Loading, Notice, PageHeader, Status } from "../../components/ui";
import type {
  PacketCatalogEntry,
  PacketMatchStatus,
  PacketObservation,
} from "../../types/dashboard";
import { downloadText } from "../../api/client";
import { messageOf, timestamp } from "../../utilities/format";

interface MonitorScope {
  key: string;
  label: string;
  tenant: string;
  proxy: string;
}

function statusState(status: PacketMatchStatus): "ok" | "warning" | "danger" | "neutral" {
  if (status === "native" || status === "automatic_codec_match") return "ok";
  if (status === "explicit_translation") return "warning";
  return "danger";
}

function statusLabel(status: PacketMatchStatus): string {
  switch (status) {
    case "native":
      return "Native match";
    case "automatic_codec_match":
      return "Auto matched";
    case "explicit_translation":
      return "Translated";
    case "review_required":
      return "Review required";
    case "unknown_packet":
      return "Unknown packet";
  }
}

function packetId(value: number): string {
  return value >= 0 ? `${value} (0x${value.toString(16).toUpperCase()})` : "Not available";
}

function PacketDetailDialog({ packet, close }: { packet: PacketObservation; close: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => dialog.current?.showModal(), []);
  return (
    <dialog
      ref={dialog}
      className="dialog packetDialog"
      aria-labelledby="packet-detail-title"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onClose={close}
    >
      <div className="sectionTitle">
        <div>
          <p className="eyebrow">Safe packet metadata</p>
          <h2 id="packet-detail-title">{packet.packetName}</h2>
        </div>
        <button className="iconButton" aria-label="Close packet details" onClick={close}>
          <X aria-hidden="true" />
        </button>
      </div>
      <Status state={statusState(packet.status)}>{statusLabel(packet.status)}</Status>
      <dl className="detailList packetDetails">
        <div>
          <dt>Observed</dt>
          <dd>{timestamp(packet.timestamp)}</dd>
        </div>
        <div>
          <dt>Direction</dt>
          <dd>{packet.directionLabel}</dd>
        </div>
        <div>
          <dt>Source codec</dt>
          <dd>
            Minecraft {packet.sourceVersion} · protocol {packet.sourceProtocol} · packet{" "}
            {packetId(packet.sourcePacketId)}
          </dd>
        </div>
        <div>
          <dt>Target codec</dt>
          <dd>
            Minecraft {packet.targetVersion} · protocol {packet.targetProtocol} · packet{" "}
            {packetId(packet.targetPacketId)}
          </dd>
        </div>
        <div>
          <dt>Proxy action</dt>
          <dd>{packet.action}</dd>
        </div>
        <div>
          <dt>Player and backend</dt>
          <dd>
            {packet.player || "Unknown player"} → {packet.backend || "Connecting"}
          </dd>
        </div>
      </dl>
      {packet.suggestion ? (
        <div className="warningBox">
          <strong>Translation research candidate</strong>
          <span>{packet.suggestion}</span>
        </div>
      ) : (
        <div className="infoBox">
          The packet has a known target model. The target codec performs the wire-format conversion.
        </div>
      )}
      <p className="fieldHint">
        Payloads, chat, login chains, tokens, XUIDs, addresses, and wire bytes are never retained by
        this monitor.
      </p>
    </dialog>
  );
}

function CatalogTable({ entries }: { entries: PacketCatalogEntry[] }) {
  if (!entries.length) {
    return (
      <Empty title="No matching packet definitions" detail="Change the search or protocol pair." />
    );
  }
  return (
    <div className="tableWrap packetCatalogTable">
      <table>
        <thead>
          <tr>
            <th>Direction</th>
            <th>Packet model</th>
            <th>Source ID</th>
            <th>Target ID</th>
            <th>Compatibility</th>
            <th>Seen live</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={`${entry.direction}-${entry.packetName}-${entry.sourcePacketId}`}>
              <td>{entry.direction === "serverbound" ? "Player → server" : "Server → player"}</td>
              <td>
                <strong>{entry.packetName}</strong>
                {entry.candidate ? <small>ID-only candidate: {entry.candidate}</small> : null}
              </td>
              <td className="mono">{packetId(entry.sourcePacketId)}</td>
              <td className="mono">{packetId(entry.targetPacketId)}</td>
              <td>
                <Status state={statusState(entry.status)}>{statusLabel(entry.status)}</Status>
              </td>
              <td>{entry.observedCount.toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function PacketMonitorPage() {
  const { principal } = useAuth();
  const tenantVisible = principal?.role === "owner" || principal?.role === "tenant";
  const tenancy = useQuery({
    queryKey: ["tenancy", "packet-monitor"],
    queryFn: ({ signal }) => dashboardApi.tenancy(signal),
    enabled: tenantVisible,
  });
  const scopes = useMemo<MonitorScope[]>(() => {
    const values: MonitorScope[] = [];
    if (principal?.role !== "tenant") {
      values.push({ key: "provider", label: "Main provider proxy", tenant: "", proxy: "" });
    }
    for (const proxy of tenancy.data?.proxies ?? []) {
      values.push({
        key: `${proxy.tenantId}/${proxy.id}`,
        label: `${proxy.label} · ${proxy.publicAddress}`,
        tenant: proxy.tenantId,
        proxy: proxy.id,
      });
    }
    return values;
  }, [principal?.role, tenancy.data?.proxies]);
  const [scopeKey, setScopeKey] = useState("");
  const activeScope = scopes.find((scope) => scope.key === scopeKey) ?? scopes[0];
  const [paused, setPaused] = useState(false);
  const [direction, setDirection] = useState("");
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [limit, setLimit] = useState(500);
  const [clientProtocol, setClientProtocol] = useState("");
  const [backendProtocol, setBackendProtocol] = useState("");
  const [selected, setSelected] = useState<PacketObservation | null>(null);
  const query = useQuery({
    queryKey: [
      "packet-monitor",
      activeScope?.key,
      direction,
      status,
      search,
      limit,
      clientProtocol,
      backendProtocol,
    ],
    queryFn: ({ signal }) =>
      dashboardApi.packets(
        {
          tenant: activeScope?.tenant ?? "",
          proxy: activeScope?.proxy ?? "",
          direction,
          status,
          q: search,
          limit,
          clientProtocol,
          backendProtocol,
        },
        signal,
      ),
    enabled: Boolean(activeScope),
    refetchInterval: paused ? false : 1_500,
  });
  const data = query.data;
  const summary = data?.summary;
  const catalogSearch = search.trim().toLowerCase();
  const catalog = useMemo(
    () =>
      (data?.catalog ?? []).filter((entry) =>
        catalogSearch
          ? `${entry.packetName} ${entry.candidate}`.toLowerCase().includes(catalogSearch)
          : true,
      ),
    [catalogSearch, data?.catalog],
  );
  const effectiveClient = clientProtocol || String(data?.selectedPair.clientProtocol ?? "");
  const effectiveBackend = backendProtocol || String(data?.selectedPair.backendProtocol ?? "");

  return (
    <>
      <PageHeader
        title="Packet Monitor"
        description="Watch safe packet metadata, verify live cross-version matches, and identify exactly which packet models need reviewed translation work."
        actions={
          <>
            <Button className="secondary" onClick={() => setPaused((value) => !value)}>
              {paused ? <Play aria-hidden="true" /> : <Pause aria-hidden="true" />}
              {paused ? "Resume live view" : "Pause live view"}
            </Button>
            <Button
              className="secondary"
              onClick={() => void query.refetch()}
              disabled={query.isFetching}
            >
              <RefreshCw aria-hidden="true" />
              Refresh
            </Button>
            <Button
              className="secondary"
              disabled={!data}
              onClick={() =>
                data &&
                downloadText(
                  `onilink-packet-compatibility-${Date.now()}.json`,
                  `${JSON.stringify(data, null, 2)}\n`,
                )
              }
            >
              <Download aria-hidden="true" />
              Export report
            </Button>
          </>
        }
      />
      <Notice message={query.error ? messageOf(query.error) : ""} error />
      {tenantVisible ? (
        <label className="proxySelector">
          Proxy to monitor
          <span className="fieldHelp">
            Tenant traffic stays isolated; each login can view only its permitted proxies.
          </span>
          <select
            value={activeScope?.key ?? ""}
            onChange={(event) => setScopeKey(event.target.value)}
            disabled={!scopes.length}
          >
            {scopes.map((scope) => (
              <option key={scope.key} value={scope.key}>
                {scope.label}
              </option>
            ))}
          </select>
        </label>
      ) : null}
      {summary ? (
        <div className="metricGrid packetMetricGrid">
          <Card className="metric">
            <Activity aria-hidden="true" />
            <span>Packets observed</span>
            <strong>{summary.observedPackets.toLocaleString()}</strong>
          </Card>
          <Card className="metric">
            <Workflow aria-hidden="true" />
            <span>Automatic matches</span>
            <strong>{summary.automaticMatches.toLocaleString()}</strong>
          </Card>
          <Card className="metric">
            <TriangleAlert aria-hidden="true" />
            <span>Review required</span>
            <strong>{summary.reviewRequired.toLocaleString()}</strong>
          </Card>
          <Card className="metric">
            <Database aria-hidden="true" />
            <span>Bounded records</span>
            <strong>
              {summary.storedRecords.toLocaleString()} / {summary.capacity.toLocaleString()}
            </strong>
          </Card>
        </div>
      ) : null}
      <Card className="packetSafety">
        <ShieldCheck aria-hidden="true" />
        <div>
          <strong>Private by design</strong>
          <p>{data?.privacy ?? "Only safe metadata is retained locally in memory."}</p>
        </div>
        <span className={`liveIndicator ${paused ? "paused" : ""}`}>
          {paused ? "View paused" : "Live · 1.5s refresh"}
        </span>
      </Card>
      <Card>
        <div className="sectionTitle">
          <div>
            <p className="eyebrow">Live traffic</p>
            <h2>Incoming packet flow</h2>
            <p>
              OniLink matches each decoded packet model against the target protocol and records the
              result produced by the real translator.
            </p>
          </div>
        </div>
        <div className="packetFilters">
          <label>
            Direction
            <select value={direction} onChange={(event) => setDirection(event.target.value)}>
              <option value="">Both directions</option>
              <option value="serverbound">Player → server</option>
              <option value="clientbound">Server → player</option>
            </select>
          </label>
          <label>
            Match result
            <select value={status} onChange={(event) => setStatus(event.target.value)}>
              <option value="">All results</option>
              <option value="native">Native match</option>
              <option value="automatic_codec_match">Automatic codec match</option>
              <option value="explicit_translation">Explicit translation</option>
              <option value="review_required">Review required</option>
              <option value="unknown_packet">Unknown packet</option>
            </select>
          </label>
          <label>
            Search packets, players, or backends
            <span className="inputWithIcon">
              <Search aria-hidden="true" />
              <input
                type="search"
                placeholder="StartGame, survival, player name…"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </span>
          </label>
          <label>
            Records shown
            <select value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
              <option value={100}>100</option>
              <option value={250}>250</option>
              <option value={500}>500</option>
              <option value={1000}>1,000</option>
            </select>
          </label>
        </div>
        {query.isLoading ? (
          <Loading label="Opening packet monitor" />
        ) : data?.records.length ? (
          <div className="tableWrap packetStreamTable">
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Direction</th>
                  <th>Packet</th>
                  <th>Protocol path</th>
                  <th>Match</th>
                  <th>Player / backend</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {data.records.map((packet) => (
                  <tr key={packet.sequence}>
                    <td>{timestamp(packet.timestamp)}</td>
                    <td>{packet.directionLabel}</td>
                    <td>
                      <button className="packetNameButton" onClick={() => setSelected(packet)}>
                        {packet.packetName}
                      </button>
                      <small>Source ID {packetId(packet.sourcePacketId)}</small>
                    </td>
                    <td className="mono">
                      {packet.sourceVersion} ({packet.sourceProtocol})
                      <small>
                        → {packet.targetVersion} ({packet.targetProtocol})
                      </small>
                    </td>
                    <td>
                      <Status state={statusState(packet.status)}>
                        {statusLabel(packet.status)}
                      </Status>
                    </td>
                    <td>
                      <strong>{packet.player || "Unknown player"}</strong>
                      <small>{packet.backend || "Connecting"}</small>
                    </td>
                    <td>{packet.action}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title="No matching packets yet"
            detail="Connect a player or change the filters. The monitor fills automatically as packets pass through this proxy."
          />
        )}
      </Card>
      <div className="twoColumn packetInsights">
        <Card>
          <p className="eyebrow">Observed evidence</p>
          <h2>Translation assistance</h2>
          <p>
            Unique live packet paths are counted here so frequent failures and missing target models
            rise to the top without storing every movement packet.
          </p>
          {data?.matches.length ? (
            <ul className="packetMatchList">
              {data.matches.slice(0, 20).map((match) => (
                <li
                  key={`${match.direction}-${match.sourceProtocol}-${match.targetProtocol}-${match.packetName}-${match.action}`}
                >
                  <div>
                    <strong>{match.packetName}</strong>
                    <small>
                      {match.direction === "serverbound" ? "Player → server" : "Server → player"}
                      {` · ${match.sourceProtocol} → ${match.targetProtocol} · ${match.action}`}
                    </small>
                    {match.suggestion ? <span>{match.suggestion}</span> : null}
                  </div>
                  <div>
                    <Status state={statusState(match.status)}>{statusLabel(match.status)}</Status>
                    <small>{match.count.toLocaleString()} seen</small>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <Empty
              title="No live matches yet"
              detail="Observed packet families will be grouped here."
            />
          )}
        </Card>
        <Card>
          <p className="eyebrow">How matching works</p>
          <h2>Safe automatic translation</h2>
          <div className="packetExplanation">
            <div>
              <Workflow aria-hidden="true" />
              <span>
                <strong>Automatic codec match</strong>
                The same shared packet model exists in both protocols. OniLink lets the target codec
                write its correct packet ID and field layout.
              </span>
            </div>
            <div>
              <Route aria-hidden="true" />
              <span>
                <strong>Explicit translation</strong>A reviewed translator changes fields, replaces
                a model, or deliberately drops a packet with no honest representation.
              </span>
            </div>
            <div>
              <TriangleAlert aria-hidden="true" />
              <span>
                <strong>Review required</strong>
                Live traffic found no target model. An equal numeric ID is shown only as a
                candidate; OniLink never guesses field meaning from production players.
              </span>
            </div>
          </div>
        </Card>
      </div>
      <Card>
        <div className="sectionTitle">
          <div>
            <p className="eyebrow">Built-in packet definitions</p>
            <h2>Cross-version packet catalog</h2>
            <p>
              Compare the packet models already compiled into OniLink. “Seen live” connects the
              static definitions to real traffic from the selected proxy.
            </p>
          </div>
          <span className="tag">{data?.catalogCount ?? 0} directional definitions</span>
        </div>
        <div className="formGrid protocolPairPicker">
          <label>
            Player/client Minecraft protocol
            <select
              value={effectiveClient}
              onChange={(event) => setClientProtocol(event.target.value)}
            >
              {(data?.protocols ?? []).map((protocol) => (
                <option key={protocol.protocol} value={protocol.protocol}>
                  Minecraft {protocol.minecraftVersion} · protocol {protocol.protocol}
                </option>
              ))}
            </select>
          </label>
          <label>
            Destination server Minecraft protocol
            <select
              value={effectiveBackend}
              onChange={(event) => setBackendProtocol(event.target.value)}
            >
              {(data?.protocols ?? []).map((protocol) => (
                <option key={protocol.protocol} value={protocol.protocol}>
                  Minecraft {protocol.minecraftVersion} · protocol {protocol.protocol}
                </option>
              ))}
            </select>
          </label>
        </div>
        {data && !data.routeAvailable ? (
          <div className="warningBox">
            <strong>No complete translator route exists for this pair.</strong>
            <span>
              Use the review-required rows and exported report to implement and test the missing
              adjacent translation edge before accepting players on this version pair.
            </span>
          </div>
        ) : null}
        <CatalogTable entries={catalog} />
      </Card>
      {selected ? <PacketDetailDialog packet={selected} close={() => setSelected(null)} /> : null}
    </>
  );
}
