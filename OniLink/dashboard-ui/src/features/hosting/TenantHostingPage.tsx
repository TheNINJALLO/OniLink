import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Download,
  Pause,
  Play,
  Plus,
  RefreshCw,
  RotateCw,
  ServerCog,
  UserPlus,
} from "lucide-react";
import { useState } from "react";
import { dashboardApi } from "../../api/dashboard";
import { Button, Card, Empty, Loading, Notice, PageHeader, Status } from "../../components/ui";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import type { Tenant } from "../../types/dashboard";
import { messageOf, timestamp } from "../../utilities/format";

export function TenantHostingPage({ navigate }: { navigate: (route: string) => void }) {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["tenancy"],
    queryFn: ({ signal }) => dashboardApi.tenancy(signal),
    refetchInterval: 10_000,
  });
  const [message, setMessage] = useState("");
  const [tenantForm, setTenantForm] = useState({
    tenant: "",
    label: "",
    username: "",
    password: "",
  });
  const [userForm, setUserForm] = useState({ tenant: "", username: "", password: "" });
  const [proxyForm, setProxyForm] = useState({
    tenant: "",
    proxy: "",
    label: "",
    port: "",
    publicHost: "",
    backendAddress: "",
    proxySourceIp: "",
    maxPlayers: "100",
    motd: "OniLink Network",
    bdsProfile: "",
  });
  const [suspend, setSuspend] = useState<Tenant | null>(null);
  const refresh = async () => client.invalidateQueries({ queryKey: ["tenancy"] });
  const createTenant = useMutation({
    mutationFn: () => dashboardApi.createTenant(tenantForm),
    onSuccess: async (result) => {
      setMessage(result.message);
      setTenantForm({ tenant: "", label: "", username: "", password: "" });
      await refresh();
    },
  });
  const createUser = useMutation({
    mutationFn: () => dashboardApi.addTenantUser(userForm),
    onSuccess: async () => {
      setMessage("Tenant user created. They can sign in through this control plane.");
      setUserForm({ tenant: "", username: "", password: "" });
      await refresh();
    },
  });
  const createProxy = useMutation({
    mutationFn: () => dashboardApi.createTenantProxy(proxyForm),
    onSuccess: async (result) => {
      setMessage(result.message);
      setProxyForm((value) => ({ ...value, proxy: "", label: "", port: "", backendAddress: "" }));
      await refresh();
    },
  });
  const tenantAction = useMutation({
    mutationFn: ({ tenant, action }: { tenant: string; action: string }) =>
      dashboardApi.tenantAction(tenant, action),
    onSuccess: async () => {
      setSuspend(null);
      setMessage("Tenant lifecycle updated.");
      await refresh();
    },
  });
  const proxyAction = useMutation({
    mutationFn: ({ tenant, proxy, action }: { tenant: string; proxy: string; action: string }) =>
      dashboardApi.tenantProxyAction(tenant, proxy, action),
    onSuccess: async (result) => {
      setMessage(result.message);
      await refresh();
    },
  });
  const activeError =
    query.error ??
    createTenant.error ??
    createUser.error ??
    createProxy.error ??
    tenantAction.error ??
    proxyAction.error;
  const tenants = query.data?.tenants ?? [];
  return (
    <>
      <PageHeader
        title="Tenant Hosting"
        description="Operate isolated customer proxies inside this OniLink container."
        actions={
          <Button className="secondary" onClick={() => void query.refetch()}>
            <RefreshCw aria-hidden="true" />
            Refresh
          </Button>
        }
      />
      <Notice message={message} />
      <Notice message={activeError ? messageOf(activeError) : ""} error />
      <Card>
        <div className="sectionTitle">
          <div>
            <p className="eyebrow">Single-container provider</p>
            <h2>Hosting boundary</h2>
          </div>
          <ServerCog aria-hidden="true" />
        </div>
        <dl className="detailList">
          <div>
            <dt>Control-plane mode</dt>
            <dd>{query.data?.mode ?? "single-container"}</dd>
          </div>
          <div>
            <dt>Provider port</dt>
            <dd>{query.data?.providerPort ?? "—"}</dd>
          </div>
          <div>
            <dt>Tenant scope</dt>
            <dd>{query.data?.tenantScope || "Isolated per account"}</dd>
          </div>
        </dl>
        <p className="fieldHint">
          Each proxy needs one unique UDP allocation on this container. Customers use this same
          dashboard and see only their assigned proxies.
        </p>
      </Card>
      <div className="threeColumn">
        <Card>
          <p className="eyebrow">Step 1</p>
          <h2>Create tenant</h2>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              createTenant.mutate();
            }}
          >
            <label>
              Tenant ID
              <input
                pattern="[a-z][a-z0-9-]{1,31}"
                required
                value={tenantForm.tenant}
                onChange={(event) => setTenantForm({ ...tenantForm, tenant: event.target.value })}
                placeholder="acme-network"
              />
            </label>
            <label>
              Display label
              <input
                required
                value={tenantForm.label}
                onChange={(event) => setTenantForm({ ...tenantForm, label: event.target.value })}
                placeholder="Acme Network"
              />
            </label>
            <label>
              Initial tenant username
              <input
                required
                value={tenantForm.username}
                onChange={(event) => setTenantForm({ ...tenantForm, username: event.target.value })}
                autoComplete="off"
              />
            </label>
            <label>
              Initial tenant password
              <input
                type="password"
                minLength={12}
                required
                value={tenantForm.password}
                onChange={(event) => setTenantForm({ ...tenantForm, password: event.target.value })}
                autoComplete="new-password"
              />
            </label>
            <Button type="submit" disabled={createTenant.isPending}>
              <Plus aria-hidden="true" />
              Create tenant
            </Button>
          </form>
        </Card>
        <Card>
          <p className="eyebrow">Optional</p>
          <h2>Add another tenant login</h2>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              createUser.mutate();
            }}
          >
            <label>
              Tenant
              <select
                required
                value={userForm.tenant}
                onChange={(event) => setUserForm({ ...userForm, tenant: event.target.value })}
              >
                <option value="">Select…</option>
                {tenants.map((tenant) => (
                  <option key={tenant.id} value={tenant.id}>
                    {tenant.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Additional username
              <input
                required
                value={userForm.username}
                onChange={(event) => setUserForm({ ...userForm, username: event.target.value })}
              />
            </label>
            <label>
              Additional password
              <input
                type="password"
                minLength={12}
                required
                value={userForm.password}
                onChange={(event) => setUserForm({ ...userForm, password: event.target.value })}
              />
            </label>
            <Button type="submit" disabled={createUser.isPending}>
              <UserPlus aria-hidden="true" />
              Create login
            </Button>
          </form>
        </Card>
        <Card>
          <p className="eyebrow">Step 3</p>
          <h2>Allocate proxy</h2>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              createProxy.mutate();
            }}
          >
            <label>
              Tenant
              <select
                required
                value={proxyForm.tenant}
                onChange={(event) => setProxyForm({ ...proxyForm, tenant: event.target.value })}
              >
                <option value="">Select…</option>
                {tenants.map((tenant) => (
                  <option key={tenant.id} value={tenant.id}>
                    {tenant.label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Proxy ID
              <input
                required
                pattern="[a-z][a-z0-9-]{1,31}"
                value={proxyForm.proxy}
                onChange={(event) => setProxyForm({ ...proxyForm, proxy: event.target.value })}
              />
            </label>
            <label>
              Label
              <input
                required
                value={proxyForm.label}
                onChange={(event) => setProxyForm({ ...proxyForm, label: event.target.value })}
              />
            </label>
            <div className="formGrid">
              <label>
                UDP port
                <input
                  type="number"
                  required
                  min="1"
                  max="65535"
                  value={proxyForm.port}
                  onChange={(event) => setProxyForm({ ...proxyForm, port: event.target.value })}
                />
              </label>
              <label>
                Max players
                <input
                  type="number"
                  min="1"
                  value={proxyForm.maxPlayers}
                  onChange={(event) =>
                    setProxyForm({ ...proxyForm, maxPlayers: event.target.value })
                  }
                />
              </label>
            </div>
            <label>
              Public host
              <input
                required
                value={proxyForm.publicHost}
                onChange={(event) => setProxyForm({ ...proxyForm, publicHost: event.target.value })}
              />
            </label>
            <label>
              Initial BDS endpoint
              <input
                required
                value={proxyForm.backendAddress}
                onChange={(event) =>
                  setProxyForm({ ...proxyForm, backendAddress: event.target.value })
                }
              />
            </label>
            <label>
              Proxy source IP
              <input
                required
                value={proxyForm.proxySourceIp}
                onChange={(event) =>
                  setProxyForm({ ...proxyForm, proxySourceIp: event.target.value })
                }
              />
            </label>
            <label>
              MOTD
              <input
                required
                value={proxyForm.motd}
                onChange={(event) => setProxyForm({ ...proxyForm, motd: event.target.value })}
              />
            </label>
            <label>
              BDS profile <span className="optional">Optional</span>
              <input
                value={proxyForm.bdsProfile}
                onChange={(event) => setProxyForm({ ...proxyForm, bdsProfile: event.target.value })}
              />
            </label>
            <Button type="submit" disabled={createProxy.isPending}>
              {createProxy.isPending ? "Starting…" : "Create and start proxy"}
            </Button>
          </form>
        </Card>
      </div>
      <Card>
        <h2>Tenants</h2>
        {query.isLoading ? (
          <Loading label="Loading tenants" />
        ) : tenants.length ? (
          <div className="tableWrap">
            <table>
              <thead>
                <tr>
                  <th>Tenant</th>
                  <th>State</th>
                  <th>Users</th>
                  <th>Updated</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {tenants.map((tenant) => (
                  <tr key={tenant.id}>
                    <td>
                      <strong>{tenant.label}</strong>
                      <small>{tenant.id}</small>
                    </td>
                    <td>
                      <Status state={tenant.suspended ? "danger" : "ok"}>
                        {tenant.suspended ? "Suspended" : "Active"}
                      </Status>
                    </td>
                    <td>{tenant.users.length}</td>
                    <td>{timestamp(tenant.updatedAt)}</td>
                    <td>
                      {tenant.suspended ? (
                        <Button
                          className="secondary compact"
                          onClick={() =>
                            tenantAction.mutate({ tenant: tenant.id, action: "restore" })
                          }
                        >
                          <Play aria-hidden="true" />
                          Restore
                        </Button>
                      ) : (
                        <Button className="danger compact" onClick={() => setSuspend(tenant)}>
                          <Pause aria-hidden="true" />
                          Suspend
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title="No tenants"
            detail="Create a tenant to establish the first isolated customer boundary."
          />
        )}
      </Card>
      <Card>
        <h2>Proxy instances</h2>
        {query.data?.proxies.length ? (
          <div className="tableWrap">
            <table>
              <thead>
                <tr>
                  <th>Proxy</th>
                  <th>Allocation</th>
                  <th>Backend</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {query.data.proxies.map((proxy) => (
                  <tr key={`${proxy.tenantId}/${proxy.id}`}>
                    <td>
                      <strong>{proxy.label}</strong>
                      <small>
                        {proxy.tenantId}/{proxy.id}
                      </small>
                    </td>
                    <td>
                      {proxy.publicAddress}:{proxy.port}
                    </td>
                    <td className="mono">{proxy.backendAddress}</td>
                    <td>
                      <Status
                        state={
                          proxy.running ? "ok" : proxy.status === "error" ? "danger" : "warning"
                        }
                      >
                        {proxy.status}
                      </Status>
                      {proxy.lastError ? <small>{proxy.lastError}</small> : null}
                    </td>
                    <td>
                      <div className="rowActions">
                        <Button
                          className="secondary compact"
                          onClick={() => {
                            sessionStorage.setItem(
                              "onilink:selected-proxy",
                              `${proxy.tenantId}/${proxy.id}`,
                            );
                            navigate("my-proxies");
                          }}
                        >
                          Open
                        </Button>
                        <Button
                          className="secondary compact"
                          onClick={() =>
                            proxyAction.mutate({
                              tenant: proxy.tenantId,
                              proxy: proxy.id,
                              action: proxy.running ? "restart" : "start",
                            })
                          }
                        >
                          {proxy.running ? (
                            <RotateCw aria-hidden="true" />
                          ) : (
                            <Play aria-hidden="true" />
                          )}
                          {proxy.running ? "Restart" : "Start"}
                        </Button>
                        {proxy.running ? (
                          <Button
                            className="danger compact"
                            onClick={() =>
                              proxyAction.mutate({
                                tenant: proxy.tenantId,
                                proxy: proxy.id,
                                action: "stop",
                              })
                            }
                          >
                            <Pause aria-hidden="true" />
                            Stop
                          </Button>
                        ) : null}
                        {proxy.handoffAvailable ? (
                          <Button
                            className="secondary compact"
                            onClick={() =>
                              void dashboardApi.tenantHandoff(proxy.tenantId, proxy.id)
                            }
                          >
                            <Download aria-hidden="true" />
                            Handoff
                          </Button>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Empty
            title="No tenant proxies"
            detail="Allocate a unique UDP port and initial backend above."
          />
        )}
      </Card>
      <ConfirmDialog
        open={Boolean(suspend)}
        title="Suspend tenant?"
        description={`${suspend?.label ?? "This tenant"} and every assigned proxy will stop. Tenant users will retain login access but cannot operate suspended runtimes.`}
        confirmLabel="Suspend tenant"
        destructive
        busy={tenantAction.isPending}
        onClose={() => setSuspend(null)}
        onConfirm={() => suspend && tenantAction.mutate({ tenant: suspend.id, action: "suspend" })}
      />
    </>
  );
}
