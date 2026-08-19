import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Search, ShieldCheck, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { dashboardApi } from "../../api/dashboard";
import {
  Button,
  Card,
  Empty,
  FieldError,
  Loading,
  Notice,
  PageHeader,
  Status,
} from "../../components/ui";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import type { AllowlistEntry } from "../../types/dashboard";
import { messageOf } from "../../utilities/format";

const schema = z.object({
  xuid: z.string().regex(/^\d{5,20}$/, "Enter a numeric XUID"),
  name: z.string().trim().max(64),
});
type Values = z.infer<typeof schema>;

export function AllowlistPage() {
  const client = useQueryClient();
  const allowlist = useQuery({
    queryKey: ["allowlist"],
    queryFn: ({ signal }) => dashboardApi.allowlist(signal),
  });
  const players = useQuery({
    queryKey: ["players"],
    queryFn: ({ signal }) => dashboardApi.players(signal),
  });
  const [search, setSearch] = useState("");
  const [remove, setRemove] = useState<AllowlistEntry | null>(null);
  const [message, setMessage] = useState("");
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { xuid: "", name: "" },
  });
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: ["allowlist"] });
  };
  const add = useMutation({
    mutationFn: (body: Values) => dashboardApi.addAllowlist(body),
    onSuccess: async (result) => {
      form.reset();
      setMessage(result.message);
      await refresh();
    },
  });
  const drop = useMutation({
    mutationFn: (xuid: string) => dashboardApi.removeAllowlist(xuid),
    onSuccess: async (result) => {
      setRemove(null);
      setMessage(result.message);
      await refresh();
    },
  });
  const entries = useMemo(
    () =>
      (allowlist.data?.entries ?? []).filter((entry) =>
        `${entry.name} ${entry.xuid}`.toLowerCase().includes(search.toLowerCase()),
      ),
    [allowlist.data, search],
  );
  const listed = new Set((allowlist.data?.entries ?? []).map((entry) => entry.xuid));
  const connected = (players.data?.players ?? []).filter(
    (player) => player.xuid && !listed.has(player.xuid),
  );
  return (
    <>
      <PageHeader
        title="Allowlist"
        description="Control admission by authoritative Xbox user ID."
        actions={
          allowlist.data ? (
            <Status state={allowlist.data.enabled ? "ok" : "warning"}>
              {allowlist.data.enabled ? "Enforced" : "Not enforced"} · {allowlist.data.count}
            </Status>
          ) : null
        }
      />
      <Notice message={message} />
      <Notice
        message={add.error ? messageOf(add.error) : drop.error ? messageOf(drop.error) : ""}
        error
      />
      <div className="twoColumn uneven">
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">New entry</p>
              <h2>Add an identity</h2>
            </div>
            <ShieldCheck aria-hidden="true" />
          </div>
          <p className="fieldHint">
            The XUID is authoritative. The gamertag is an operator label and may change.
          </p>
          {connected.length ? (
            <label>
              Connected player
              <select
                defaultValue=""
                onChange={(event) => {
                  const player = connected.find((item) => item.xuid === event.target.value);
                  if (player) {
                    form.setValue("xuid", player.xuid);
                    form.setValue("name", player.name);
                  }
                }}
              >
                <option value="">Choose a connected player…</option>
                {connected.map((player) => (
                  <option value={player.xuid} key={player.xuid}>
                    {player.name} · {player.xuid}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <p className="fieldHint">No unlisted players are currently connected.</p>
          )}
          <form onSubmit={form.handleSubmit((values) => add.mutate(values))} noValidate>
            <label>
              XUID
              <input
                inputMode="numeric"
                {...form.register("xuid")}
                aria-describedby="allow-xuid-error"
              />
            </label>
            <FieldError id="allow-xuid-error">{form.formState.errors.xuid?.message}</FieldError>
            <label>
              Gamertag label <span className="optional">Optional</span>
              <input {...form.register("name")} />
            </label>
            <Button type="submit" disabled={add.isPending}>
              <Plus aria-hidden="true" />
              {add.isPending ? "Adding…" : "Add entry"}
            </Button>
          </form>
        </Card>
        <Card>
          <label className="searchField">
            <Search aria-hidden="true" />
            <span className="srOnly">Search allowlist</span>
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search gamertag or XUID…"
            />
          </label>
          {allowlist.isLoading ? (
            <Loading label="Loading allowlist" />
          ) : entries.length ? (
            <ul className="itemList">
              {entries.map((entry) => (
                <li key={entry.xuid}>
                  <span>
                    <strong>{entry.name || "Unlabeled identity"}</strong>
                    <small className="mono">{entry.xuid}</small>
                  </span>
                  <Button
                    className="danger compact"
                    aria-label={`Remove ${entry.name || entry.xuid}`}
                    onClick={() => setRemove(entry)}
                  >
                    <Trash2 aria-hidden="true" />
                    Remove
                  </Button>
                </li>
              ))}
            </ul>
          ) : (
            <Empty
              title={search ? "No matching entries" : "Allowlist is empty"}
              detail="Add a connected player or enter a XUID manually."
            />
          )}
        </Card>
      </div>
      <ConfirmDialog
        open={Boolean(remove)}
        title="Remove allowlist entry?"
        description={`XUID ${remove?.xuid ?? ""} will no longer be authorized${allowlist.data?.disconnectOnRemoval ? " and may be disconnected immediately" : ""}.`}
        confirmLabel="Remove entry"
        destructive
        busy={drop.isPending}
        onClose={() => setRemove(null)}
        onConfirm={() => remove && drop.mutate(remove.xuid)}
      />
    </>
  );
}
