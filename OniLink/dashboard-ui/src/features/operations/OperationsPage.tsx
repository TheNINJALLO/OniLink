import { useMutation, useQuery } from "@tanstack/react-query";
import { Clipboard, Download, RefreshCw, Send, WrapText } from "lucide-react";
import { useMemo, useState } from "react";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Card, Notice, PageHeader } from "../../components/ui";
import { hasRole } from "../../permissions/roles";
import { messageOf } from "../../utilities/format";

export function OperationsPage() {
  const { principal } = useAuth();
  const [limit, setLimit] = useState(400);
  const [search, setSearch] = useState("");
  const [wrap, setWrap] = useState(false);
  const [alert, setAlert] = useState("");
  const [shutdownText, setShutdownText] = useState("");
  const [message, setMessage] = useState("");
  const logs = useQuery({
    queryKey: ["logs", limit],
    queryFn: ({ signal }) => dashboardApi.logs(limit, signal),
    enabled: Boolean(principal && hasRole(principal.role, "operator")),
  });
  const send = useMutation({
    mutationFn: () => dashboardApi.action("alert", { message: alert }),
    onSuccess: (result) => {
      setMessage(result.message);
      setAlert("");
    },
  });
  const shutdown = useMutation({
    mutationFn: dashboardApi.shutdown,
    onSuccess: (result) => setMessage(result.message),
  });
  const support = useMutation({
    mutationFn: dashboardApi.supportBundle,
    onSuccess: () => setMessage("Redacted support bundle downloaded."),
  });
  const filtered = useMemo(
    () =>
      (logs.data?.lines ?? []).filter((line) => line.toLowerCase().includes(search.toLowerCase())),
    [logs.data, search],
  );
  const owner = principal?.role === "owner";
  async function copyLogs() {
    try {
      await navigator.clipboard.writeText(filtered.join("\n"));
      setMessage("Visible log lines copied.");
    } catch {
      setMessage("Clipboard access was denied by the browser.");
    }
  }
  return (
    <>
      <PageHeader
        title="Operations"
        description="Live messaging, diagnostics, support artifacts, and lifecycle controls."
      />
      <Notice message={message} />
      <Notice
        message={
          send.error
            ? messageOf(send.error)
            : logs.error
              ? messageOf(logs.error)
              : support.error
                ? messageOf(support.error)
                : shutdown.error
                  ? messageOf(shutdown.error)
                  : ""
        }
        error
      />
      <div className="twoColumn">
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Network message</p>
              <h2>Broadcast alert</h2>
            </div>
            <Send aria-hidden="true" />
          </div>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              send.mutate();
            }}
          >
            <label>
              Message
              <textarea
                rows={4}
                required
                maxLength={512}
                value={alert}
                onChange={(event) => setAlert(event.target.value)}
              />
            </label>
            <Button type="submit" disabled={send.isPending || !alert.trim()}>
              {send.isPending ? "Sending…" : "Send to all players"}
            </Button>
          </form>
        </Card>
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Portable diagnostics</p>
              <h2>Support bundle</h2>
            </div>
            <Download aria-hidden="true" />
          </div>
          <p>
            Download a server-generated ZIP with protected values redacted for safe troubleshooting.
          </p>
          <Button onClick={() => support.mutate()} disabled={support.isPending}>
            <Download aria-hidden="true" />
            {support.isPending ? "Preparing…" : "Download support bundle"}
          </Button>
        </Card>
      </div>
      <Card>
        <div className="sectionTitle">
          <div>
            <p className="eyebrow">Live diagnostics</p>
            <h2>Log tail</h2>
          </div>
          <div className="buttonRow">
            <Button className="secondary compact" onClick={() => void logs.refetch()}>
              <RefreshCw aria-hidden="true" />
              Refresh
            </Button>
            <Button
              className={`secondary compact ${wrap ? "selected" : ""}`}
              aria-pressed={wrap}
              onClick={() => setWrap(!wrap)}
            >
              <WrapText aria-hidden="true" />
              Wrap
            </Button>
            <Button className="secondary compact" onClick={() => void copyLogs()}>
              <Clipboard aria-hidden="true" />
              Copy
            </Button>
          </div>
        </div>
        <div className="filterRow">
          <label>
            Search
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
          <label>
            Line limit
            <select value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
              <option>100</option>
              <option>250</option>
              <option>400</option>
              <option>1000</option>
            </select>
          </label>
        </div>
        <pre className={`logView ${wrap ? "wrap" : ""}`} tabIndex={0}>
          {filtered.length ? filtered.join("\n") : "No matching log lines."}
        </pre>
      </Card>
      {owner ? (
        <Card className="dangerZone">
          <p className="eyebrow">Owner-only danger zone</p>
          <h2>Graceful proxy shutdown</h2>
          <p>
            Connected players will be disconnected and the OniLink process will stop. Type{" "}
            <strong>SHUTDOWN</strong> to enable the control.
          </p>
          <label>
            Confirmation
            <input
              value={shutdownText}
              onChange={(event) => setShutdownText(event.target.value)}
              autoComplete="off"
            />
          </label>
          <Button
            className="danger"
            disabled={shutdownText !== "SHUTDOWN" || shutdown.isPending}
            onClick={() => shutdown.mutate()}
          >
            {shutdown.isPending ? "Stopping…" : "Gracefully shut down OniLink"}
          </Button>
        </Card>
      ) : null}
    </>
  );
}
