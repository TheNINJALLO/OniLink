import {
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type SortingState,
} from "@tanstack/react-table";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRightLeft, Bug, LogOut, RefreshCw, Search } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Empty, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { hasRole } from "../../permissions/roles";
import type { Player } from "../../types/dashboard";
import { duration, messageOf } from "../../utilities/format";

type PlayerAction = "transfer" | "disconnect" | "trace";

function PlayerActionDialog({
  player,
  action,
  backends,
  close,
  done,
}: {
  player: Player;
  action: PlayerAction;
  backends: string[];
  close: () => void;
  done: (message: string) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [backend, setBackend] = useState(backends[0] ?? "");
  const [reason, setReason] = useState("Disconnected by an operator");
  const [milliseconds, setMilliseconds] = useState(10_000);
  const mutation = useMutation({
    mutationFn: () =>
      dashboardApi.action(action, {
        player: player.name,
        ...(action === "transfer" ? { backend } : {}),
        ...(action === "disconnect" ? { reason } : {}),
        ...(action === "trace" ? { milliseconds } : {}),
      }),
    onSuccess: (result) => done(result.message),
  });
  useEffect(() => {
    dialog.current?.showModal();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="dialog"
      aria-labelledby="player-action-title"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onClose={close}
    >
      <p className="eyebrow">Player operation</p>
      <h2 id="player-action-title">
        {action === "transfer"
          ? "Transfer"
          : action === "disconnect"
            ? "Disconnect"
            : "Capture packet trace"}{" "}
        · {player.name}
      </h2>
      <p>
        {action === "trace"
          ? "Tracing is bounded and stops automatically."
          : "This action changes the player's live connection after the proxy confirms it."}
      </p>
      {action === "transfer" ? (
        <label>
          Destination backend
          <select value={backend} onChange={(event) => setBackend(event.target.value)} required>
            {backends.map((name) => (
              <option key={name}>{name}</option>
            ))}
          </select>
        </label>
      ) : null}
      {action === "disconnect" ? (
        <label>
          Reason
          <input value={reason} onChange={(event) => setReason(event.target.value)} required />
        </label>
      ) : null}
      {action === "trace" ? (
        <label>
          Trace duration (milliseconds)
          <input
            type="number"
            min="1000"
            max="30000"
            step="1000"
            value={milliseconds}
            onChange={(event) => setMilliseconds(Number(event.target.value))}
          />
        </label>
      ) : null}
      <Notice message={mutation.error ? messageOf(mutation.error) : ""} error />
      <div className="dialogActions">
        <Button className="secondary" onClick={close} disabled={mutation.isPending}>
          Cancel
        </Button>
        <Button
          className={action === "disconnect" ? "danger" : ""}
          onClick={() => mutation.mutate()}
          disabled={mutation.isPending || (action === "transfer" && !backend)}
        >
          {mutation.isPending ? "Submitting…" : `Confirm ${action}`}
        </Button>
      </div>
    </dialog>
  );
}

export function PlayersPage() {
  const { principal } = useAuth();
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: ["players"],
    queryFn: ({ signal }) => dashboardApi.players(signal),
    refetchInterval: 5_000,
  });
  const backends = useQuery({
    queryKey: ["backends"],
    queryFn: ({ signal }) => dashboardApi.backends(signal),
  });
  const [filter, setFilter] = useState("");
  const [sorting, setSorting] = useState<SortingState>([{ id: "name", desc: false }]);
  const [selected, setSelected] = useState<{ player: Player; action: PlayerAction } | null>(null);
  const [notice, setNotice] = useState("");
  const operator = principal ? hasRole(principal.role, "operator") : false;
  const columns = useMemo<ColumnDef<Player>[]>(
    () => [
      {
        accessorKey: "name",
        header: "Player",
        cell: ({ row }) => (
          <>
            <strong>{row.original.name}</strong>
            <small>{row.original.identity || row.original.xuid}</small>
          </>
        ),
      },
      { accessorKey: "backend", header: "Current route" },
      { accessorKey: "protocol", header: "Protocol" },
      {
        accessorKey: "connectedMillis",
        header: "Connected",
        cell: ({ getValue }) => duration(getValue<number>()),
      },
      {
        accessorKey: "joinedWorld",
        header: "State",
        cell: ({ row }) => (
          <Status
            state={row.original.switching ? "warning" : row.original.joinedWorld ? "ok" : "neutral"}
          >
            {row.original.switching
              ? `Switching to ${row.original.switchTarget}`
              : row.original.joinedWorld
                ? "In world"
                : "Joining"}
          </Status>
        ),
      },
      ...(operator
        ? [
            {
              accessorKey: "address",
              header: "Source address",
              cell: ({ getValue }: { getValue: () => unknown }) => (
                <span className="mono">
                  {typeof getValue() === "string" && getValue() ? (getValue() as string) : "—"}
                </span>
              ),
            } as ColumnDef<Player>,
          ]
        : []),
      ...(operator
        ? [
            {
              id: "actions",
              header: "Actions",
              enableSorting: false,
              cell: ({ row }: { row: { original: Player } }) => (
                <div className="rowActions">
                  <Button
                    className="secondary compact"
                    onClick={() => setSelected({ player: row.original, action: "transfer" })}
                  >
                    <ArrowRightLeft aria-hidden="true" />
                    Transfer
                  </Button>
                  <Button
                    className="secondary compact"
                    onClick={() => setSelected({ player: row.original, action: "trace" })}
                  >
                    <Bug aria-hidden="true" />
                    Trace
                  </Button>
                  <Button
                    className="danger compact"
                    onClick={() => setSelected({ player: row.original, action: "disconnect" })}
                  >
                    <LogOut aria-hidden="true" />
                    Disconnect
                  </Button>
                </div>
              ),
            } as ColumnDef<Player>,
          ]
        : []),
    ],
    [operator],
  );
  const table = useReactTable({
    data: query.data?.players ?? [],
    columns,
    state: { globalFilter: filter, sorting },
    onGlobalFilterChange: setFilter,
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });
  return (
    <>
      <PageHeader
        title="Players"
        description="Authenticated identities and their live backend routes."
        actions={
          <Button
            className="secondary"
            onClick={() => void query.refetch()}
            disabled={query.isFetching}
          >
            <RefreshCw aria-hidden="true" />
            Refresh
          </Button>
        }
      />
      <Notice message={notice} />
      <label className="searchField">
        <Search aria-hidden="true" />
        <span className="srOnly">Search players</span>
        <input
          type="search"
          placeholder="Search name, identity, route…"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
        />
      </label>
      {query.isLoading ? (
        <Loading label="Loading players" />
      ) : table.getRowModel().rows.length ? (
        <div className="tableWrap">
          <table>
            <thead>
              {table.getHeaderGroups().map((group) => (
                <tr key={group.id}>
                  {group.headers.map((header) => (
                    <th key={header.id}>
                      <button
                        className="sortButton"
                        onClick={header.column.getToggleSortingHandler()}
                        disabled={!header.column.getCanSort()}
                      >
                        {flexRender(header.column.columnDef.header, header.getContext())}
                        {header.column.getIsSorted() === "asc"
                          ? " ↑"
                          : header.column.getIsSorted() === "desc"
                            ? " ↓"
                            : ""}
                      </button>
                    </th>
                  ))}
                </tr>
              ))}
            </thead>
            <tbody>
              {table.getRowModel().rows.map((row) => (
                <tr key={row.id}>
                  {row.getVisibleCells().map((cell) => (
                    <td key={cell.id}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Empty
          title={filter ? "No matching players" : "No players connected"}
          detail={
            filter ? "Try a different search." : "Connected Bedrock players will appear here."
          }
        />
      )}
      {selected ? (
        <PlayerActionDialog
          {...selected}
          backends={(backends.data?.backends ?? []).map((backend) => backend.name)}
          close={() => setSelected(null)}
          done={(message) => {
            setSelected(null);
            setNotice(message);
            void queryClient.invalidateQueries({ queryKey: ["players"] });
          }}
        />
      ) : null}
    </>
  );
}
