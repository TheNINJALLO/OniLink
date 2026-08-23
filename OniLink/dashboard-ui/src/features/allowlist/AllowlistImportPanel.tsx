import { useMutation } from "@tanstack/react-query";
import { FileUp } from "lucide-react";
import { useState } from "react";
import { Button, Notice } from "../../components/ui";
import type { AllowlistImportResult } from "../../types/dashboard";
import { messageOf } from "../../utilities/format";

const maximumBytes = 131_072;

export function AllowlistImportPanel({
  submit,
  complete,
}: {
  submit: (content: string, mode: "merge" | "replace") => Promise<AllowlistImportResult>;
  complete: (result: AllowlistImportResult) => void | Promise<void>;
}) {
  const [content, setContent] = useState("");
  const [filename, setFilename] = useState("");
  const [mode, setMode] = useState<"merge" | "replace">("merge");
  const [fileError, setFileError] = useState("");
  const [result, setResult] = useState<AllowlistImportResult | null>(null);
  const mutation = useMutation({
    mutationFn: () => submit(content, mode),
    onSuccess: async (value) => {
      setResult(value);
      setContent("");
      setFilename("");
      await complete(value);
    },
  });

  return (
    <div className="connectionSection">
      <h3>Import an existing allowlist</h3>
      <p>
        Accepts OniLink JSON/properties, CSV or TSV with an XUID column, and one numeric XUID per
        line. A vanilla BDS name-only file needs XUIDs added before it can be imported securely.
      </p>
      <label>
        Allowlist file
        <input
          type="file"
          accept=".json,.properties,.csv,.tsv,.txt,application/json,text/plain,text/csv"
          onChange={async (event) => {
            const file = event.target.files?.[0];
            setFileError("");
            setResult(null);
            if (!file) {
              setContent("");
              setFilename("");
              return;
            }
            if (file.size > maximumBytes) {
              setContent("");
              setFilename("");
              setFileError("Allowlist files are limited to 128 KiB.");
              event.target.value = "";
              return;
            }
            try {
              setContent(await file.text());
              setFilename(file.name);
            } catch {
              setFileError("The selected file could not be read.");
            }
          }}
        />
      </label>
      <label>
        Import behavior
        <select value={mode} onChange={(event) => setMode(event.target.value as typeof mode)}>
          <option value="merge">Merge — keep current entries and add/update imported XUIDs</option>
          <option value="replace">Replace — remove entries that are not in this file</option>
        </select>
      </label>
      {mode === "replace" ? (
        <div className="warningBox">
          Replace removes every current XUID absent from the file. The server rejects empty,
          name-only, or partially invalid replacement files.
        </div>
      ) : null}
      <Notice message={fileError || (mutation.error ? messageOf(mutation.error) : "")} error />
      {result?.rejected ? (
        <div className="infoBox">
          <strong>{result.rejected} row(s) were rejected.</strong>
          {result.errors.length ? <span>{result.errors.join(" · ")}</span> : null}
        </div>
      ) : null}
      <Button
        type="button"
        disabled={!content || mutation.isPending}
        onClick={() => {
          if (
            mode === "merge" ||
            window.confirm(
              `Replace the current allowlist with entries from ${filename || "this file"}?`,
            )
          )
            mutation.mutate();
        }}
      >
        <FileUp aria-hidden="true" />
        {mutation.isPending ? "Importing…" : `Import${filename ? ` ${filename}` : " allowlist"}`}
      </Button>
    </div>
  );
}
