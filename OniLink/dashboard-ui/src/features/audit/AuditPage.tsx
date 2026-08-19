import {
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type SortingState,
} from "@tanstack/react-table";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { dashboardApi } from "../../api/dashboard";
import { Button, Empty, Loading, PageHeader, Status } from "../../components/ui";
import type { AuditEvent } from "../../types/dashboard";
import { timestamp } from "../../utilities/format";

function scalar(value: unknown, fallback: string): string {
  return typeof value === "string" || typeof value === "number" || typeof value === "boolean"
    ? String(value)
    : fallback;
}

export function parseAuditLine(line: string): AuditEvent {
  try {
    const value = JSON.parse(line) as Record<string, unknown>;
    return {
      timestamp: scalar(value.timestamp ?? value.time, ""),
      actor: scalar(value.actor, "system"),
      role: scalar(value.role, "—"),
      remoteAddress: scalar(value.remoteAddress ?? value.source, "—"),
      action: scalar(value.action, "unknown"),
      result: scalar(value.result, "unknown"),
      details: value.details ?? "",
      raw: line,
    };
  } catch {
    return {
      timestamp: "",
      actor: "unparsed",
      role: "—",
      remoteAddress: "—",
      action: "Unparsed record",
      result: "unknown",
      details: line,
      raw: line,
    };
  }
}

export function AuditPage() {
  const query = useQuery({
    queryKey: ["audit"],
    queryFn: ({ signal }) => dashboardApi.audit(500, signal),
  });
  const [search, setSearch] = useState("");
  const [sorting, setSorting] = useState<SortingState>([{ id: "timestamp", desc: true }]);
  const rows = useMemo(() => (query.data?.lines ?? []).map(parseAuditLine), [query.data]);
  const columns = useMemo<ColumnDef<AuditEvent>[]>(
    () => [
      {
        accessorKey: "timestamp",
        header: "Timestamp",
        cell: ({ getValue }) => timestamp(getValue<string>()),
      },
      {
        accessorKey: "actor",
        header: "Actor",
        cell: ({ row }) => (
          <>
            <strong>{row.original.actor}</strong>
            <small>{row.original.role}</small>
          </>
        ),
      },
      { accessorKey: "remoteAddress", header: "Source" },
      { accessorKey: "action", header: "Action" },
      {
        accessorKey: "result",
        header: "Result",
        cell: ({ getValue }) => {
          const value = getValue<string>();
          return (
            <Status
              state={
                value === "success" || value === "allowed"
                  ? "ok"
                  : value === "denied" || value === "failed"
                    ? "danger"
                    : "neutral"
              }
            >
              {value}
            </Status>
          );
        },
      },
      {
        accessorKey: "details",
        header: "Details",
        cell: ({ getValue }) => (
          <code className="auditDetails">
            {typeof getValue() === "string" ? getValue<string>() : JSON.stringify(getValue())}
          </code>
        ),
      },
    ],
    [],
  );
  const table = useReactTable({
    data: rows,
    columns,
    state: { globalFilter: search, sorting },
    onGlobalFilterChange: setSearch,
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });
  return (
    <>
      <PageHeader
        title="Audit"
        description="Security and operator actions recorded by the control plane."
        actions={
          <Button className="secondary" onClick={() => void query.refetch()}>
            <RefreshCw aria-hidden="true" />
            Refresh
          </Button>
        }
      />
      <label className="searchField">
        <Search aria-hidden="true" />
        <span className="srOnly">Search audit records</span>
        <input
          type="search"
          placeholder="Search actor, action, result…"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </label>
      {query.isLoading ? (
        <Loading label="Loading audit records" />
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
          title="No audit records"
          detail="Recorded security and operator events will appear here."
        />
      )}
    </>
  );
}
