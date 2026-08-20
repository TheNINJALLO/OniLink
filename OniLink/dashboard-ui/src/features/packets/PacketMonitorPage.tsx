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
import { bytes, messageOf, timestamp } from "../../utilities/format";

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

function wireHexPreview(base64 = "", maximumBytes = 4_096): string {
  if (!base64) return "";
  try {
    const encodedCharacters = Math.ceil(maximumBytes / 3) * 4;
    const binary = window.atob(base64.slice(0, encodedCharacters)).slice(0, maximumBytes);
    const lines: string[] = [];
    for (let offset = 0; offset < binary.length; offset += 16) {
      const chunk = binary.slice(offset, offset + 16);
      const hex = Array.from(chunk, (character) =>
        character.charCodeAt(0).toString(16).padStart(2, "0"),
      )
        .join(" ")
        .padEnd(47, " ");
      const ascii = Array.from(chunk, (character) => {
        const code = character.charCodeAt(0);
        return code >= 32 && code <= 126 ? character : ".";
      }).join("");
      lines.push(`${offset.toString(16).padStart(8, "0")}  ${hex}  |${ascii}|`);
    }
    return lines.join("\n");
  } catch {
    return "Unable to decode the stored packet bytes.";
  }
}

function PacketDetailDialog({
  packet,
  scope,
  close,
}: {
  packet: PacketObservation;
  scope?: MonitorScope;
  close: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const detailQuery = useQuery({
    queryKey: ["packet-monitor-detail", scope?.key, packet.sequence],
    queryFn: ({ signal }) =>
      dashboardApi.packets(
        {
          tenant: scope?.tenant ?? "",
          proxy: scope?.proxy ?? "",
          sequence: packet.sequence,
          includeDetails: 1,
          limit: 1,
        },
        signal,
      ),
  });
  const detail = detailQuery.data?.records[0] ?? packet;
  const hex = wireHexPreview(detail.wireBytesBase64);
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
          <p className="eyebrow">Detailed packet capture</p>
          <h2 id="packet-detail-title">{detail.packetName}</h2>
        </div>
        <button className="iconButton" aria-label="Close packet details" onClick={close}>
          <X aria-hidden="true" />
        </button>
      </div>
      <Status state={statusState(detail.status)}>{statusLabel(detail.status)}</Status>
      <dl className="detailList packetDetails">
        <div>
          <dt>Observed</dt>
          <dd>{timestamp(detail.timestamp)}</dd>
        </div>
        <div>
          <dt>Direction</dt>
          <dd>{detail.directionLabel}</dd>
        </div>
        <div>
          <dt>Source codec</dt>
          <dd>
            Minecraft {detail.sourceVersion} · protocol {detail.sourceProtocol} · packet{" "}
            {packetId(detail.sourcePacketId)}
          </dd>
        </div>
        <div>
          <dt>Target codec</dt>
          <dd>
            Minecraft {detail.targetVersion} · protocol {detail.targetProtocol} · packet{" "}
            {packetId(detail.targetPacketId)}
          </dd>
        </div>
        <div>
          <dt>Proxy action</dt>
          <dd>{detail.action}</dd>
        </div>
        <div>
          <dt>Player and backend</dt>
          <dd>
            {detail.player || "Unknown player"} → {detail.backend || "Connecting"}
          </dd>
        </div>
        <div>
          <dt>Authenticated XUID</dt>
          <dd className="mono">{detail.xuid || "Not available"}</dd>
        </div>
        <div>
          <dt>Player endpoint</dt>
          <dd className="mono">{detail.clientAddress || "Not available"}</dd>
        </div>
        <div>
          <dt>Backend endpoint</dt>
          <dd className="mono">{detail.backendAddress || "Not available"}</dd>
        </div>
        <div>
          <dt>Inbound packet bytes</dt>
          <dd>
            {bytes(detail.wireBytesLength)} total · {detail.wireHeaderLength} header bytes
          </dd>
        </div>
      </dl>
      {detail.suggestion ? (
        <div className="warningBox">
          <strong>Translation research candidate</strong>
          <span>{detail.suggestion}</span>
        </div>
      ) : (
        <div className="infoBox">
          The packet has a known target model. The target codec performs the wire-format conversion.
        </div>
      )}
      {detailQuery.isLoading ? <Loading label="Loading packet body" /> : null}
      {detailQuery.error ? <Notice message={messageOf(detailQuery.error)} error /> : null}
      {detail.tokenRedacted ? (
        <div className="warningBox">
          <strong>Authentication material redacted</strong>
          <span>{detail.redactionReason}</span>
        </div>
      ) : null}
      {detail.decodedPayload ? (
        <section className="packetPayloadSection">
          <h3>Decoded source packet</h3>
          <pre>{detail.decodedPayload}</pre>
        </section>
      ) : null}
      {detail.translatedPayload ? (
        <section className="packetPayloadSection">
          <h3>Translated target packet</h3>
          <pre>{detail.translatedPayload}</pre>
        </section>
      ) : null}
      {hex ? (
        <section className="packetPayloadSection">
          <h3>Incoming bytes</h3>
          <p className="fieldHint">
            Exact uncompressed packet bytes, including the {detail.wireHeaderLength}-byte Bedrock
            header. This preview shows the first {Math.min(detail.wireBytesLength, 4_096)} bytes;
            the full Base64 value is included in a full capture export.
          </p>
          <pre>{hex}</pre>
        </section>
      ) : null}
      <p className="fieldHint">
        Packet bodies, chat, XUIDs, endpoints, and incoming bytes live only in the bounded in-memory
        capture. Authentication tokens are always removed.
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
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState("");
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
  const exportCapture = async () => {
    setExporting(true);
    setExportError("");
    try {
      const capture = await dashboardApi.packets({
        tenant: activeScope?.tenant ?? "",
        proxy: activeScope?.proxy ?? "",
        direction,
        status,
        q: search,
        limit,
        clientProtocol,
        backendProtocol,
        includeDetails: 1,
      });
      downloadText(
        `onilink-packet-capture-${Date.now()}.json`,
        `${JSON.stringify(capture, null, 2)}\n`,
      );
    } catch (error) {
      setExportError(messageOf(error));
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Packet Monitor"
        description="Inspect decoded packet bodies, chat, player identity, endpoints, incoming bytes, and live cross-version matches without retaining authentication tokens."
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
              disabled={!data || exporting}
              onClick={() => void exportCapture()}
            >
              <Download aria-hidden="true" />
              {exporting ? "Building capture…" : "Export full capture"}
            </Button>
          </>
        }
      />
      <Notice message={query.error ? messageOf(query.error) : exportError} error />
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
            <span>Capture memory</span>
            <strong>
              {bytes(summary.retainedCaptureBytes)} / {bytes(summary.captureBudgetBytes)}
            </strong>
            <small>{summary.storedRecords.toLocaleString()} packet records</small>
          </Card>
        </div>
      ) : null}
      <Card className="packetSafety">
        <ShieldCheck aria-hidden="true" />
        <div>
          <strong>Detailed capture with token redaction</strong>
          <p>
            {data?.privacy ??
              "Packet contents are retained only in a bounded local memory window; tokens are redacted."}
          </p>
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
              OniLink stores each sampled packet's decoded fields and exact incoming bytes, then
              matches its model against the target protocol and records the real translator result.
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
            Search packets, payloads, players, XUIDs, addresses, or backends
            <span className="inputWithIcon">
              <Search aria-hidden="true" />
              <input
                type="search"
                placeholder="StartGame, chat text, XUID, IP, backend…"
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
      {selected ? (
        <PacketDetailDialog packet={selected} scope={activeScope} close={() => setSelected(null)} />
      ) : null}
    </>
  );
}
