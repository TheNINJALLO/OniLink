import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Download, KeyRound, LockKeyhole } from "lucide-react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { downloadBase64, downloadText } from "../../api/client";
import { dashboardApi } from "../../api/dashboard";
import { ConnectionPath } from "../../components/ConnectionPath";
import { Button, Card, FieldError, Loading, Notice, PageHeader } from "../../components/ui";
import type { BackendSetup } from "../../types/dashboard";
import { endpoint, messageOf } from "../../utilities/format";

const port = z
  .string()
  .trim()
  .regex(/^\d+$/, "Enter the UDP port assigned to the game server")
  .refine((value) => Number(value) >= 1 && Number(value) <= 65535, "Port must be 1-65535");

const schema = z.object({
  name: z.string().regex(/^[a-z][a-z0-9_-]{0,31}$/, "Use 1–32 lowercase route characters"),
  backendHost: z.string().trim().min(3, "Destination server IP or domain is required").max(253),
  backendPort: port,
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
  const runtime = useQuery({
    queryKey: ["state"],
    queryFn: ({ signal }) => dashboardApi.state(signal),
  });
  const [step, setStep] = useState(0);
  const [result, setResult] = useState<BackendSetup | null>(null);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: "",
      backendHost: "",
      backendPort: "",
      proxyPublicIp: "",
      bridgeId: "",
      activeKeyId: "key-1",
    },
  });
  const mutation = useMutation({
    mutationFn: (values: Values) =>
      dashboardApi.addBackend({
        name: values.name,
        address: endpoint(values.backendHost, values.backendPort),
        proxyPublicIp: values.proxyPublicIp,
        bridgeId: values.bridgeId,
        activeKeyId: values.activeKeyId,
        revision: config.data?.revision ?? "",
      }),
    onSuccess: (data) => {
      setResult(data);
      setStep(4);
    },
  });
  const values = form.watch();
  const steps = ["Game server", "Proxy address", "Security", "Confirm", "Download"];
  async function next() {
    const fields: Array<Array<keyof Values>> = [
      ["name", "backendHost", "backendPort"],
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
          description="Tell OniLink where the game server is. The proxy address is shown separately."
        />
        <Loading label="Loading configuration revision" />
      </>
    );
  return (
    <>
      <PageHeader
        title="Add Backend"
        description="Players join the OniLink proxy; OniLink then forwards them to the destination game server."
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
                <legend>Where should OniLink send the players?</legend>
                <p>Enter the IP address and UDP port of the Bedrock server running OniBridge.</p>
                <label>
                  Backend route name
                  <span className="fieldHelp">
                    A short internal name shown in OniLink, such as survival or creative.
                  </span>
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
                <div className="connectionSection">
                  <h3>Destination game server</h3>
                  <p>This is the BDS/Endstone server OniLink will forward players to.</p>
                  <div className="formGrid">
                    <label>
                      Destination server IP or domain
                      <span className="fieldHelp">Example: 45.143.196.160</span>
                      <input
                        placeholder="45.143.196.160"
                        autoComplete="off"
                        {...form.register("backendHost")}
                        aria-describedby="backend-host-error"
                      />
                    </label>
                    <label>
                      Destination server UDP port
                      <span className="fieldHelp">Example: 25570</span>
                      <input
                        type="number"
                        min="1"
                        max="65535"
                        placeholder="25570"
                        {...form.register("backendPort")}
                        aria-describedby="backend-port-error"
                      />
                    </label>
                  </div>
                  <FieldError id="backend-host-error">
                    {form.formState.errors.backendHost?.message}
                  </FieldError>
                  <FieldError id="backend-port-error">
                    {form.formState.errors.backendPort?.message}
                  </FieldError>
                </div>
              </fieldset>
            ) : null}
            {step === 1 ? (
              <fieldset>
                <legend>Which OniLink proxy is sending the players?</legend>
                <p>The game server trusts connections coming from this proxy IP.</p>
                <div className="connectionSection">
                  <h3>Player-facing proxy</h3>
                  <p>
                    This is the OniLink address players connect to before they reach the backend.
                  </p>
                  <div className="formGrid">
                    <label>
                      Proxy IP seen by the game server
                      <span className="fieldHelp">
                        Usually the public IP of this OniLink server. Do not include a port.
                      </span>
                      <input
                        placeholder="45.143.196.108"
                        autoComplete="off"
                        {...form.register("proxyPublicIp")}
                        aria-describedby="proxy-ip-error"
                      />
                    </label>
                    <label>
                      Proxy UDP port players join
                      <span className="fieldHelp">
                        Already configured; adding a backend needs no new proxy port.
                      </span>
                      <input readOnly value={runtime.data?.listener.port ?? "Loading..."} />
                    </label>
                  </div>
                </div>
                <FieldError id="proxy-ip-error">
                  {form.formState.errors.proxyPublicIp?.message}
                </FieldError>
                <div className="infoBox">
                  OniLink converts the proxy IP into an exact trusted CIDR. Every backend attached
                  to this proxy uses the same player-facing proxy port.
                </div>
              </fieldset>
            ) : null}
            {step === 2 ? (
              <fieldset>
                <legend>Security labels</legend>
                <p>
                  Most users can leave these values at their defaults. OniLink generates the secret
                  automatically.
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
                <legend>Confirm where players connect and where they go</legend>
                <ConnectionPath
                  proxyEndpoint={endpoint(values.proxyPublicIp, runtime.data?.listener.port ?? "")}
                  destinationEndpoint={endpoint(values.backendHost, values.backendPort)}
                />
                <dl className="reviewList">
                  <div>
                    <dt>Internal route name</dt>
                    <dd>{values.name}</dd>
                  </div>
                  <div>
                    <dt>Destination game server</dt>
                    <dd>{endpoint(values.backendHost, values.backendPort)}</dd>
                  </div>
                  <div>
                    <dt>Trusted proxy IP</dt>
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
