import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw, Save } from "lucide-react";
import { useState } from "react";
import { ApiError } from "../../api/client";
import { dashboardApi } from "../../api/dashboard";
import { Button, Card, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { messageOf } from "../../utilities/format";

export function ConfigurationPage() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["config"],
    queryFn: ({ signal }) => dashboardApi.config(signal),
  });
  const [content, setContent] = useState<string | null>(null);
  const [rollback, setRollback] = useState(false);
  const [message, setMessage] = useState("");
  const editorContent = content ?? query.data?.content ?? "";
  const save = useMutation({
    mutationFn: () => dashboardApi.saveConfig(query.data?.revision ?? "", editorContent),
    onSuccess: (data) => {
      client.setQueryData(["config"], data);
      setContent(data.content);
      setMessage(
        data.message ||
          "Configuration validated, backed up, and saved. Restart OniLink to apply it.",
      );
    },
  });
  const restore = useMutation({
    mutationFn: dashboardApi.rollbackConfig,
    onSuccess: (data) => {
      client.setQueryData(["config"], data);
      setContent(data.content);
      setRollback(false);
      setMessage("Previous configuration restored. Restart OniLink to apply it.");
    },
  });
  const conflict = save.error instanceof ApiError && save.error.kind === "conflict";
  return (
    <>
      <PageHeader
        title="Configuration"
        description="Edit the redacted runtime configuration with revision protection."
        actions={
          query.data ? (
            <Status state="neutral">Revision {query.data.revision.slice(0, 12)}</Status>
          ) : null
        }
      />
      <Notice message={message} />
      <Notice
        message={
          save.error
            ? conflict
              ? "Configuration changed on disk. Reload before making another edit."
              : messageOf(save.error)
            : restore.error
              ? messageOf(restore.error)
              : ""
        }
        error
      />
      {query.isLoading ? (
        <Loading label="Loading redacted configuration" />
      ) : query.data ? (
        <Card>
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Protected editor</p>
              <h2>config.properties</h2>
            </div>
            <span className="tag">Secrets redacted</span>
          </div>
          <p className="fieldHint">
            Protected placeholders are restored server-side. The parser validates the complete file
            before it replaces the active configuration.
          </p>
          <label className="srOnly" htmlFor="config-editor">
            Configuration content
          </label>
          <textarea
            id="config-editor"
            className="configEditor mono"
            spellCheck="false"
            value={editorContent}
            onChange={(event) => setContent(event.target.value)}
          />
          <div className="buttonRow">
            <Button
              onClick={() => save.mutate()}
              disabled={save.isPending || editorContent === query.data.content}
            >
              <Save aria-hidden="true" />
              {save.isPending ? "Validating…" : "Validate and save"}
            </Button>
            <Button
              className="secondary"
              onClick={() => setRollback(true)}
              disabled={!query.data.backupAvailable}
            >
              <RotateCcw aria-hidden="true" />
              Restore backup
            </Button>
            {conflict ? (
              <Button
                className="secondary"
                onClick={() =>
                  void query.refetch().then((result) => {
                    if (result.data) setContent(result.data.content);
                  })
                }
              >
                Reload disk version
              </Button>
            ) : null}
          </div>
        </Card>
      ) : (
        <Notice
          message={query.error ? messageOf(query.error) : "Configuration is unavailable."}
          error
        />
      )}
      <ConfirmDialog
        open={rollback}
        title="Restore the last backup?"
        description="The current dashboard configuration will be replaced by the previous validated backup. A restart is required to apply it."
        confirmLabel="Restore backup"
        destructive
        busy={restore.isPending}
        onClose={() => setRollback(false)}
        onConfirm={() => restore.mutate()}
      />
    </>
  );
}
