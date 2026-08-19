import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Download, KeyRound, LockKeyhole } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { downloadBase64, downloadText } from "../../api/client";
import { dashboardApi } from "../../api/dashboard";
import { Button, Card, FieldError, Loading, Notice, PageHeader } from "../../components/ui";
import type { BackendSetup } from "../../types/dashboard";
import { messageOf } from "../../utilities/format";

const schema = z.object({
  name: z.string().regex(/^[a-z][a-z0-9_-]{0,31}$/, "Use 1–32 lowercase route characters"),
  address: z.string().trim().min(3, "BDS address is required").max(320),
  proxyPublicIp: z.string().trim().min(3, "Proxy public IP is required").max(64),
  bridgeId: z.string().trim().max(64),
  activeKeyId: z.string().trim().min(1, "Key label is required").max(64),
});
type Values = z.infer<typeof schema>;

export function AddBackendPage() {
  const config = useQuery({
    queryKey: ["config"],
    queryFn: ({ signal }) => dashboardApi.config(signal),
  });
  const [step, setStep] = useState(0);
  const [result, setResult] = useState<BackendSetup | null>(null);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { name: "", address: "", proxyPublicIp: "", bridgeId: "", activeKeyId: "key-1" },
  });
  const mutation = useMutation({
    mutationFn: (values: Values) =>
      dashboardApi.addBackend({ ...values, revision: config.data?.revision ?? "" }),
    onSuccess: (data) => {
      setResult(data);
      setStep(4);
    },
  });
  const values = form.watch();
  const steps = ["Identity", "Proxy trust", "Bridge key", "Review", "Result"];
  async function next() {
    const fields: Array<Array<keyof Values>> = [
      ["name", "address"],
      ["proxyPublicIp"],
      ["bridgeId", "activeKeyId"],
    ];
    if (step < 3 && (await form.trigger(fields[step]))) setStep(step + 1);
  }
  if (!config.data && config.isLoading)
    return (
      <>
        <PageHeader
          title="Add Backend"
          description="Generate a secured OniBridge route and installation bundle."
        />
        <Loading label="Loading configuration revision" />
      </>
    );
  return (
    <>
      <PageHeader
        title="Add Backend"
        description="A guided setup for the OniLink route, trusted source, and bridge key."
      />
      <ol className="stepper" aria-label="Backend setup progress">
        {steps.map((label, index) => (
          <li
            key={label}
            className={index === step ? "current" : index < step ? "complete" : ""}
            aria-current={index === step ? "step" : undefined}
          >
            <span>{index < step ? <Check aria-hidden="true" /> : index + 1}</span>
            {label}
          </li>
        ))}
      </ol>
      <Notice message={mutation.error ? messageOf(mutation.error) : ""} error />
      {result ? (
        <Card className="resultCard">
          <div className="sectionTitle">
            <div>
              <p className="eyebrow">Generated once</p>
              <h2>{result.backendName} is ready to install</h2>
            </div>
            <LockKeyhole aria-hidden="true" />
          </div>
          <p>{result.message}</p>
          <div className="warningBox">
            <strong>Save these materials now.</strong>
            <span>
              The generated forwarding secret is held only in this view and is cleared when you
              dismiss it.
            </span>
          </div>
          <label>
            One-time forwarding secret
            <textarea className="mono" readOnly rows={2} value={result.secret} />
          </label>
          <label>
            Saved OniLink properties
            <textarea className="mono" readOnly rows={6} value={result.onilinkProperties} />
          </label>
          <label>
            OniBridge configuration
            <textarea className="mono" readOnly rows={12} value={result.onibridgeToml} />
          </label>
          <div className="buttonRow">
            <Button
              onClick={() =>
                downloadBase64(
                  result.setupBundleFileName,
                  result.setupBundleBase64,
                  "application/zip",
                )
              }
            >
              <Download aria-hidden="true" />
              Download setup ZIP
            </Button>
            <Button
              className="secondary"
              onClick={() => downloadText(result.secretFileName, `${result.secret}\n`)}
            >
              <KeyRound aria-hidden="true" />
              Download key
            </Button>
            <Button
              className="secondary"
              onClick={() => downloadText("onibridge.toml", result.onibridgeToml)}
            >
              Download TOML
            </Button>
            <Button
              className="danger"
              onClick={() => {
                setResult(null);
                setStep(0);
                form.reset();
              }}
            >
              Clear result
            </Button>
          </div>
        </Card>
      ) : (
        <Card>
          <form onSubmit={form.handleSubmit((data) => mutation.mutate(data))} noValidate>
            {step === 0 ? (
              <fieldset>
                <legend>Backend identity and private endpoint</legend>
                <p>Name the route and enter the BDS allocation exactly as shown by the host.</p>
                <label>
                  Backend name
                  <input
                    placeholder="creative"
                    autoComplete="off"
                    {...form.register("name")}
                    aria-describedby="backend-name-error"
                  />
                </label>
                <FieldError id="backend-name-error">
                  {form.formState.errors.name?.message}
                </FieldError>
                <label>
                  BDS allocation
                  <input
                    placeholder="45.143.196.160:25570"
                    autoComplete="off"
                    {...form.register("address")}
                    aria-describedby="backend-address-error"
                  />
                </label>
                <FieldError id="backend-address-error">
                  {form.formState.errors.address?.message}
                </FieldError>
              </fieldset>
            ) : null}
            {step === 1 ? (
              <fieldset>
                <legend>Proxy source trust</legend>
                <p>Use the public IP that BDS sees for OniLink. Do not include the player port.</p>
                <label>
                  OniLink public IP
                  <input
                    placeholder="45.143.196.108"
                    autoComplete="off"
                    {...form.register("proxyPublicIp")}
                    aria-describedby="proxy-ip-error"
                  />
                </label>
                <FieldError id="proxy-ip-error">
                  {form.formState.errors.proxyPublicIp?.message}
                </FieldError>
                <div className="infoBox">
                  The server creates the exact IPv4 /32 or IPv6 /128 CIDR. One OniLink player
                  allocation serves every backend.
                </div>
              </fieldset>
            ) : null}
            {step === 2 ? (
              <fieldset>
                <legend>Bridge and key settings</legend>
                <p>
                  Labels identify the bridge and key rotation. The server generates the secret
                  securely.
                </p>
                <label>
                  Bridge ID <span className="optional">Optional</span>
                  <input
                    placeholder={`${values.name || "backend"}-main`}
                    autoComplete="off"
                    {...form.register("bridgeId")}
                  />
                </label>
                <label>
                  Active key ID
                  <input
                    autoComplete="off"
                    {...form.register("activeKeyId")}
                    aria-describedby="key-id-error"
                  />
                </label>
                <FieldError id="key-id-error">
                  {form.formState.errors.activeKeyId?.message}
                </FieldError>
              </fieldset>
            ) : null}
            {step === 3 ? (
              <fieldset>
                <legend>Review secured route</legend>
                <dl className="reviewList">
                  <div>
                    <dt>Route</dt>
                    <dd>{values.name}</dd>
                  </div>
                  <div>
                    <dt>Private BDS endpoint</dt>
                    <dd>{values.address}</dd>
                  </div>
                  <div>
                    <dt>Trusted proxy</dt>
                    <dd>{values.proxyPublicIp}</dd>
                  </div>
                  <div>
                    <dt>Bridge ID</dt>
                    <dd>{values.bridgeId || `${values.name}-main`}</dd>
                  </div>
                  <div>
                    <dt>Key label</dt>
                    <dd>{values.activeKeyId}</dd>
                  </div>
                  <div>
                    <dt>Configuration revision</dt>
                    <dd className="mono">{config.data?.revision ?? "Unavailable"}</dd>
                  </div>
                </dl>
                <div className="warningBox">
                  <strong>One-time secret</strong>
                  <span>
                    Creating the route updates and backs up configuration, creates an owner-only key
                    file, and returns the secret only once.
                  </span>
                </div>
              </fieldset>
            ) : null}
            <div className="wizardActions">
              {step > 0 ? (
                <Button type="button" className="secondary" onClick={() => setStep(step - 1)}>
                  <ChevronLeft aria-hidden="true" />
                  Back
                </Button>
              ) : (
                <span />
              )}
              {step < 3 ? (
                <Button type="button" onClick={() => void next()}>
                  Continue
                  <ChevronRight aria-hidden="true" />
                </Button>
              ) : (
                <Button type="submit" disabled={mutation.isPending || !config.data}>
                  {mutation.isPending ? "Building secure package…" : "Create backend setup package"}
                </Button>
              )}
            </div>
          </form>
        </Card>
      )}
    </>
  );
}
