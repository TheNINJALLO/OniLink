import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { History, RefreshCw, Save, Send, ShieldCheck } from "lucide-react";
import { useMemo, useState } from "react";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { Button, Card, Empty, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { hasRole } from "../../permissions/roles";
import type { OniControlPlanPreview, OniControlPreview } from "../../types/dashboard";
import { messageOf, timestamp } from "../../utilities/format";

const CLIENT_ACTIONS = [
  "SEND_MESSAGE",
  "SEND_TITLE",
  "SEND_SUBTITLE",
  "SEND_ACTIONBAR",
  "SEND_TOAST",
  "SHOW_FORM",
  "CLOSE_FORM",
  "SHOW_OVERLAY",
  "HIDE_OVERLAY",
  "SCREEN_FADE",
  "PLAY_SOUND",
  "STOP_SOUND",
  "SPAWN_PARTICLE",
  "SET_PRIVATE_WEATHER",
  "CLEAR_PRIVATE_WEATHER",
  "SET_HUD_VISIBILITY",
  "RESET_HUD_VISIBILITY",
  "CLEAR_CAMERA",
  "CREATE_BOSSBAR",
  "UPDATE_BOSSBAR",
  "REMOVE_BOSSBAR",
  "START_PACKET_TRACE",
  "STOP_PACKET_TRACE",
  "KICK_PLAYER",
  "TRANSFER_PLAYER",
  "GET_PLAYER_STATE",
  "GET_PLAYER_POSITION",
  "OPEN_VIRTUAL_INVENTORY",
  "CLOSE_VIRTUAL_INVENTORY",
];
const ADMIN_ACTIONS = [
  "GET_PLAYER_INVENTORY",
  "GET_PLAYER_PERMISSIONS",
  "TELEPORT",
  "CHANGE_DIMENSION",
  "GIVE_ITEM",
  "REMOVE_ITEM",
  "SET_INVENTORY_SLOT",
  "SET_ARMOR_SLOT",
  "SET_OFFHAND_SLOT",
  "CLEAR_INVENTORY",
  "DAMAGE_ITEM",
  "REPAIR_ITEM",
  "SET_HEALTH",
  "HEAL",
  "DAMAGE",
  "SET_EXPERIENCE",
  "ADD_EXPERIENCE",
  "SET_LEVEL",
  "SET_GAMEMODE",
  "SET_ABILITY",
  "ADD_TAG",
  "REMOVE_TAG",
  "SET_PERMISSION",
  "SET_BLOCK",
  "FILL_BLOCK_REGION",
  "SPAWN_ENTITY",
  "REMOVE_ENTITY",
  "SPAWN_PRIVATE_ENTITY",
  "UPDATE_PRIVATE_ENTITY",
  "SET_PRIVATE_ENTITY_METADATA",
  "MOVE_PRIVATE_ENTITY",
  "REMOVE_PRIVATE_ENTITY",
  "CLEAR_PRIVATE_ENTITIES",
  "SPAWN_PRIVATE_NPC",
  "SPAWN_PRIVATE_HOLOGRAM",
  "SET_FAKE_BLOCK",
  "SET_FAKE_BLOCK_REGION",
  "RESTORE_FAKE_BLOCK",
  "CLEAR_FAKE_BLOCKS",
];
const OWNER_ACTIONS: string[] = [];

function human(value: string) {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function OniControlPage() {
  const { principal } = useAuth();
  const queryClient = useQueryClient();
  const tenant = principal?.role === "tenant";
  const tenancy = useQuery({
    queryKey: ["tenancy", "onicontrol"],
    queryFn: ({ signal }) => dashboardApi.tenancy(signal),
    enabled: tenant,
  });
  const [proxy, setProxy] = useState("");
  const tenantProxies = tenancy.data?.proxies.filter((item) => item.enabled) ?? [];
  const scopedProxy = tenant ? proxy || tenantProxies[0]?.id || "" : "";
  const status = useQuery({
    queryKey: ["onicontrol-status", scopedProxy],
    queryFn: ({ signal }) => dashboardApi.oniControlStatus(scopedProxy, signal),
    enabled: !tenant || Boolean(scopedProxy),
    refetchInterval: 5_000,
  });
  const players = useQuery({
    queryKey: ["players", scopedProxy],
    queryFn: ({ signal }) =>
      tenant ? dashboardApi.playersForProxy(scopedProxy, signal) : dashboardApi.players(signal),
    enabled: !tenant || Boolean(scopedProxy),
    refetchInterval: 5_000,
  });
  const history = useQuery({
    queryKey: ["onicontrol-history", scopedProxy],
    queryFn: ({ signal }) => dashboardApi.oniControlHistory(scopedProxy, signal),
    enabled: !tenant || Boolean(scopedProxy),
    refetchInterval: 10_000,
  });
  const rules = useQuery({
    queryKey: ["onipacket-rules", scopedProxy],
    queryFn: ({ signal }) => dashboardApi.oniPacketRules(scopedProxy, signal),
    enabled: principal ? hasRole(principal.role, "admin") : false,
  });
  const protocolLab = useQuery({
    queryKey: ["protocol-lab", scopedProxy],
    queryFn: ({ signal }) => dashboardApi.protocolLabStatus(scopedProxy, signal),
    enabled: principal?.role === "owner",
  });
  const [player, setPlayer] = useState("");
  const [action, setAction] = useState("SEND_MESSAGE");
  const [payload, setPayload] = useState('{"message":"Welcome to the network"}');
  const [reason, setReason] = useState("");
  const [preview, setPreview] = useState<OniControlPreview | null>(null);
  const [planPreview, setPlanPreview] = useState<OniControlPlanPreview | null>(null);
  const [planText, setPlanText] = useState("");
  const [notice, setNotice] = useState("");
  const [ruleText, setRuleText] = useState<string | null>(null);
  const [labModel, setLabModel] = useState("SYSTEM_MESSAGE");
  const [labPayload, setLabPayload] = useState('{"message":"Protocol Lab test"}');
  const [labResult, setLabResult] = useState("");
  const operator = principal
    ? principal.role === "tenant" || hasRole(principal.role, "operator")
    : false;
  const admin = principal ? hasRole(principal.role, "admin") : false;
  const owner = principal?.role === "owner";
  const effectivePlayer = player || players.data?.players[0]?.xuid || "";
  const selected = players.data?.players.find((candidate) => candidate.xuid === effectivePlayer);
  const capabilities = useQuery({
    queryKey: ["onicontrol-capabilities", scopedProxy, effectivePlayer, selected?.backend],
    queryFn: ({ signal }) =>
      dashboardApi.oniControlCapabilities(
        effectivePlayer,
        selected?.backend ?? "",
        scopedProxy,
        signal,
      ),
    enabled: Boolean(effectivePlayer && selected?.backend && (!tenant || scopedProxy)),
  });
  const displayedRuleText = ruleText ?? (rules.data ? JSON.stringify(rules.data, null, 2) : "");
  const actions = useMemo(() => {
    const roleActions = admin
      ? [...CLIENT_ACTIONS, ...ADMIN_ACTIONS, ...(owner ? OWNER_ACTIONS : [])]
      : CLIENT_ACTIONS;
    if (!capabilities.data) return roleActions;
    const supported = new Set(
      capabilities.data.actions.filter((item) => item.supported).map((item) => item.action),
    );
    return roleActions.filter((item) => supported.has(item));
  }, [admin, owner, capabilities.data]);
  const effectiveAction = actions.includes(action) ? action : (actions[0] ?? "");

  const prepare = useMutation({
    mutationFn: () =>
      dashboardApi.oniControlPreview({
        xuid: effectivePlayer,
        backend: selected?.backend ?? "",
        action: effectiveAction,
        payload,
        reason,
        proxy: scopedProxy,
      }),
    onSuccess: setPreview,
  });
  const execute = useMutation({
    mutationFn: (current: OniControlPreview) =>
      dashboardApi.oniControlExecute(current.confirmationToken, true, scopedProxy),
    onSuccess: (result) => {
      setNotice(
        result.success
          ? `${human(result.status)} · ${result.auditReference}`
          : `${human(result.status)} · ${result.reason}`,
      );
      setPreview(null);
      void queryClient.invalidateQueries({ queryKey: ["onicontrol-history"] });
    },
  });
  const saveRules = useMutation({
    mutationFn: () => dashboardApi.saveOniPacketRules(displayedRuleText, scopedProxy),
    onSuccess: (result) => {
      setNotice(result.message);
      void queryClient.invalidateQueries({ queryKey: ["onipacket-rules"] });
      void queryClient.invalidateQueries({ queryKey: ["onicontrol-status"] });
    },
  });
  const preparePlan = useMutation({
    mutationFn: () => dashboardApi.oniControlPlanPreview(planText, scopedProxy),
    onSuccess: setPlanPreview,
  });
  const executePlan = useMutation({
    mutationFn: (current: OniControlPlanPreview) =>
      dashboardApi.oniControlPlanExecute(current.confirmationToken, true, scopedProxy),
    onSuccess: (result) => {
      setNotice(`${human(result.status)} · ${result.results.length} step result(s)`);
      setPlanPreview(null);
      void queryClient.invalidateQueries({ queryKey: ["onicontrol-history"] });
    },
  });
  const labSession = useMutation({
    mutationFn: (start: boolean) => dashboardApi.protocolLabSession(start, scopedProxy),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ["protocol-lab"] }),
  });
  const runLab = useMutation({
    mutationFn: (send: boolean) =>
      dashboardApi.protocolLabValidate(
        {
          xuid: effectivePlayer,
          backend: selected?.backend ?? "",
          direction: "CLIENTBOUND",
          model: labModel,
          payload: labPayload,
          proxy: scopedProxy,
        },
        send,
      ),
    onSuccess: (result) => setLabResult(JSON.stringify(result, null, 2)),
  });

  if (status.isLoading) return <Loading label="Loading OniControl" />;
  return (
    <>
      <PageHeader
        title="OniControl"
        description="Typed, authenticated player actions and packet policy for this proxy scope."
        actions={
          <Button className="secondary" onClick={() => void status.refetch()}>
            <RefreshCw aria-hidden="true" /> Refresh
          </Button>
        }
      />
      <Notice message={notice} />
      <Notice
        message={
          status.error
            ? messageOf(status.error)
            : prepare.error
              ? messageOf(prepare.error)
              : execute.error
                ? messageOf(execute.error)
                : saveRules.error
                  ? messageOf(saveRules.error)
                  : capabilities.error
                    ? messageOf(capabilities.error)
                    : preparePlan.error
                      ? messageOf(preparePlan.error)
                      : executePlan.error
                        ? messageOf(executePlan.error)
                        : ""
        }
        error
      />

      {tenant ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Tenant scope</p>
              <h2>Proxy instance</h2>
            </div>
          </div>
          {tenantProxies.length ? (
            <label>
              Operate this proxy
              <select
                value={scopedProxy}
                onChange={(event) => {
                  setProxy(event.target.value);
                  setPlayer("");
                  setPreview(null);
                  setPlanPreview(null);
                }}
              >
                {tenantProxies.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.label} · {item.publicAddress}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <Empty
              title="No enabled proxy"
              detail="Ask the provider owner to create or enable a proxy before using OniControl."
            />
          )}
        </Card>
      ) : null}

      <div className="statGrid controlStats">
        <Card>
          <span className="metricLabel">OniControl</span>
          <strong>{status.data?.controlEnabled ? "Enabled" : "Disabled"}</strong>
          <Status state={status.data?.controlEnabled ? "ok" : "neutral"}>
            {status.data?.controlEnabled ? "Requests permitted" : "Safe default"}
          </Status>
        </Card>
        <Card>
          <span className="metricLabel">Bridge links</span>
          <strong>{status.data?.bridges.filter((bridge) => bridge.connected).length ?? 0}</strong>
          <small>{status.data?.bridges.length ?? 0} configured</small>
        </Card>
        <Card>
          <span className="metricLabel">Packet rules</span>
          <strong>{status.data?.ruleCount ?? 0}</strong>
          <Status state={status.data?.packetRulesEnabled ? "ok" : "neutral"}>
            {status.data?.packetRulesEnabled ? "Live" : "Disabled"}
          </Status>
        </Card>
        <Card>
          <span className="metricLabel">Virtualization</span>
          <strong>{status.data?.virtualizationEnabled ? "Enabled" : "Disabled"}</strong>
          <small>
            {status.data?.virtualInventorySessions.length ?? 0} menus ·{" "}
            {status.data?.privateEntities.length ?? 0} entities ·{" "}
            {status.data?.fakeBlocks.length ?? 0} blocks
          </small>
        </Card>
      </div>

      <Card>
        <div className="sectionTitle">
          <div>
            <p className="eyebrow">Authenticated links</p>
            <h2>OniBridge status</h2>
          </div>
          <ShieldCheck aria-hidden="true" />
        </div>
        {status.data?.bridges.length ? (
          <div className="tableWrap">
            <table>
              <thead>
                <tr>
                  <th>Backend</th>
                  <th>Connection</th>
                  <th>Transport</th>
                  <th>Revision</th>
                  <th>Latency</th>
                  <th>Queue</th>
                  <th>Last error</th>
                </tr>
              </thead>
              <tbody>
                {status.data.bridges.map((bridge) => (
                  <tr key={`${bridge.backend}-${bridge.bridgeId}`}>
                    <td>
                      <strong>{bridge.backend}</strong>
                      <small>{bridge.bridgeId}</small>
                    </td>
                    <td>
                      <Status state={bridge.connected ? "ok" : "danger"}>
                        {bridge.connected ? "Connected" : "Offline"}
                      </Status>
                    </td>
                    <td>{bridge.tls ? "TLS" : "Private TCP"}</td>
                    <td>{bridge.capabilityRevision || "—"}</td>
                    <td>{bridge.latencyMillis} ms</td>
                    <td>{bridge.queueSize}</td>
                    <td>{bridge.lastError || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title="No control bridges"
            detail="Enable a backend's separate OniControl connection to negotiate capabilities."
          />
        )}
      </Card>

      {operator ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Typed execution</p>
              <h2>Player action</h2>
            </div>
            <span className="tag">Preview required</span>
          </div>
          <div className="controlFormGrid">
            <label>
              Authenticated player
              <select value={effectivePlayer} onChange={(event) => setPlayer(event.target.value)}>
                {(players.data?.players ?? []).map((item) => (
                  <option key={item.xuid} value={item.xuid}>
                    {item.name} · {item.backend}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Typed action
              <select value={effectiveAction} onChange={(event) => setAction(event.target.value)}>
                {actions.map((item) => (
                  <option key={item} value={item}>
                    {human(item)}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <label>
            Validated JSON parameters
            <textarea
              className="controlPayload mono"
              spellCheck="false"
              value={payload}
              onChange={(event) => setPayload(event.target.value)}
            />
          </label>
          <label>
            Operator reason (optional)
            <input
              value={reason}
              maxLength={512}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <p className="fieldHint">
            The server freezes the resolved XUID, connection, backend, protocol pair, and scope
            before issuing a single-use token.
          </p>
          <Button
            onClick={() => prepare.mutate()}
            disabled={
              !effectivePlayer ||
              !effectiveAction ||
              prepare.isPending ||
              !status.data?.controlEnabled
            }
          >
            <Send aria-hidden="true" /> {prepare.isPending ? "Validating…" : "Validate and preview"}
          </Button>
        </Card>
      ) : null}

      {operator ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Deterministic automation</p>
              <h2>Typed action plan</h2>
            </div>
            <span className="tag">No raw packets</span>
          </div>
          <p className="fieldHint">
            Plans contain at most 16 allowlisted semantic actions. The server resolves the target,
            validates every capability, freezes the revision, and issues a single-use confirmation
            token.
          </p>
          <textarea
            className="configEditor mono ruleEditor"
            spellCheck="false"
            placeholder='{"target":{"xuid":"..."},"steps":[{"action":"SEND_MESSAGE","payload":{"message":"Hello"}}],"failurePolicy":"STOP_ON_FAILURE"}'
            value={planText}
            onChange={(event) => setPlanText(event.target.value)}
          />
          <div className="buttonRow">
            <Button
              className="secondary"
              onClick={() =>
                setPlanText(
                  JSON.stringify(
                    {
                      target: { xuid: effectivePlayer, backend: selected?.backend ?? "" },
                      steps: [
                        {
                          stepId: "message",
                          action: "SEND_MESSAGE",
                          payload: { message: "Welcome to the network" },
                        },
                      ],
                      failurePolicy: "STOP_ON_FAILURE",
                      reason: "Operator-created plan",
                      confidence: 1,
                    },
                    null,
                    2,
                  ),
                )
              }
              disabled={!effectivePlayer}
            >
              Load selected-player example
            </Button>
            <Button
              onClick={() => preparePlan.mutate()}
              disabled={!planText.trim() || preparePlan.isPending || !status.data?.controlEnabled}
            >
              {preparePlan.isPending ? "Validating…" : "Validate and preview plan"}
            </Button>
          </div>
        </Card>
      ) : null}

      {admin ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">OniPacket</p>
              <h2>Scoped rule document</h2>
            </div>
            <span className="tag">Atomic replace</span>
          </div>
          <p className="fieldHint">
            Rules are schema validated, tenant/proxy scoped, saved with a backup, and swapped into
            the relay as one immutable snapshot.
          </p>
          <textarea
            className="configEditor mono ruleEditor"
            spellCheck="false"
            value={displayedRuleText}
            onChange={(event) => setRuleText(event.target.value)}
          />
          <Button
            onClick={() => saveRules.mutate()}
            disabled={saveRules.isPending || !status.data?.packetRulesEnabled}
          >
            <Save aria-hidden="true" />{" "}
            {saveRules.isPending ? "Validating…" : "Validate and activate rules"}
          </Button>
        </Card>
      ) : null}

      {owner ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Owner-only testing</p>
              <h2>Protocol Lab</h2>
            </div>
            <Status state={protocolLab.data?.sessionActive ? "warning" : "neutral"}>
              {protocolLab.data?.sessionActive ? "Timed session active" : "Stopped"}
            </Status>
          </div>
          <p className="fieldHint">
            Only configured test XUIDs and backends are accepted. Every model is semantic and dry
            encoded; login, authentication, token-bearing, unknown, and raw-byte packets are denied.
          </p>
          <div className="controlFormGrid">
            <label>
              Reviewed packet model
              <select value={labModel} onChange={(event) => setLabModel(event.target.value)}>
                {(protocolLab.data?.models ?? []).map((item) => (
                  <option key={item.model} value={item.model}>
                    {human(item.model)}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Direction
              <select value="CLIENTBOUND" disabled>
                <option value="CLIENTBOUND">Clientbound</option>
              </select>
            </label>
          </div>
          <label>
            Schema fields
            <textarea
              className="controlPayload mono"
              spellCheck="false"
              value={labPayload}
              onChange={(event) => setLabPayload(event.target.value)}
            />
          </label>
          <div className="buttonRow">
            <Button
              className="secondary"
              disabled={!protocolLab.data?.enabled || labSession.isPending}
              onClick={() => labSession.mutate(!protocolLab.data?.sessionActive)}
            >
              {protocolLab.data?.sessionActive ? "Stop session" : "Start timed session"}
            </Button>
            <Button
              className="secondary"
              disabled={!protocolLab.data?.sessionActive || !effectivePlayer || runLab.isPending}
              onClick={() => runLab.mutate(false)}
            >
              Dry-run encode
            </Button>
            <Button
              disabled={!protocolLab.data?.sessionActive || !effectivePlayer || runLab.isPending}
              onClick={() => runLab.mutate(true)}
            >
              Send test packet
            </Button>
          </div>
          {labResult ? <pre>{labResult}</pre> : null}
          <Notice
            message={
              !protocolLab.data?.enabled
                ? "Protocol Lab is disabled in configuration."
                : runLab.error
                  ? messageOf(runLab.error)
                  : labSession.error
                    ? messageOf(labSession.error)
                    : ""
            }
            error={Boolean(runLab.error || labSession.error)}
          />
        </Card>
      ) : null}

      <Card>
        <div className="sectionTitle">
          <div>
            <p className="eyebrow">Audit trail</p>
            <h2>Action history</h2>
          </div>
          <History aria-hidden="true" />
        </div>
        {history.data?.history.length ? (
          <div className="tableWrap">
            <table>
              <thead>
                <tr>
                  <th>Created</th>
                  <th>Actor</th>
                  <th>Target</th>
                  <th>Action</th>
                  <th>Plane</th>
                  <th>Status</th>
                  <th>Duration</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {history.data.history.map((item) => (
                  <tr key={item.requestId}>
                    <td>{timestamp(item.timestamp)}</td>
                    <td>
                      {item.actor}
                      <small>{item.role}</small>
                    </td>
                    <td>
                      {item.displayLabel}
                      <small>
                        {item.backend} · {item.targetXuid}
                      </small>
                    </td>
                    <td>{human(item.action)}</td>
                    <td>{human(item.executionPlane)}</td>
                    <td>
                      <Status
                        state={
                          item.status === "CONFIRMED"
                            ? "ok"
                            : item.status === "FAILED" || item.status === "REJECTED"
                              ? "danger"
                              : "warning"
                        }
                      >
                        {human(item.status)}
                      </Status>
                    </td>
                    <td>{item.durationMillis} ms</td>
                    <td>{item.failureReason || "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title="No control actions yet"
            detail="Validated actions appear here after execution."
          />
        )}
      </Card>

      <ConfirmDialog
        open={Boolean(preview)}
        title={
          preview ? `${human(preview.action)} for ${preview.target.displayName}?` : "Confirm action"
        }
        description={
          preview
            ? `${
                preview.executionPlane === "CLIENT_ONLY"
                  ? "Visual/client-only"
                  : preview.executionPlane === "BACKEND_AUTHORITATIVE"
                    ? "Backend-authoritative"
                    : "Virtualized"
              } action for XUID ${preview.target.xuid} on ${preview.target.backend}. Fields: ${preview.payloadSummary.fields.join(", ") || "none"}.`
            : ""
        }
        confirmLabel="Execute typed action"
        destructive={preview?.destructive}
        busy={execute.isPending}
        onClose={() => setPreview(null)}
        onConfirm={() => preview && execute.mutate(preview)}
      />
      <ConfirmDialog
        open={Boolean(planPreview)}
        title={planPreview ? `Execute ${planPreview.stepCount}-step typed plan?` : "Confirm plan"}
        description={
          planPreview
            ? `Plan ${planPreview.planId}, revision ${planPreview.revision}, failure policy ${human(planPreview.failurePolicy)}, required role ${human(planPreview.requiredRole)}.`
            : ""
        }
        confirmLabel="Execute typed plan"
        destructive
        busy={executePlan.isPending}
        onClose={() => setPlanPreview(null)}
        onConfirm={() => planPreview && executePlan.mutate(planPreview)}
      />
    </>
  );
}
