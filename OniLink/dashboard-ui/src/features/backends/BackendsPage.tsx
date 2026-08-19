import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { dashboardApi } from "../../api/dashboard";
import { useAuth } from "../../auth/AuthProvider";
import { Button, Card, Empty, Loading, PageHeader, Status } from "../../components/ui";
import { hasRole } from "../../permissions/roles";

export function BackendsPage({ navigate }: { navigate: (route: string) => void }) {
  const { principal } = useAuth();
  const query = useQuery({
    queryKey: ["backends"],
    queryFn: ({ signal }) => dashboardApi.backends(signal),
    refetchInterval: 10_000,
  });
  const canSeeEndpoint = principal ? hasRole(principal.role, "admin") : false;
  return (
    <>
      <PageHeader
        title="Backends"
        description="Health, routing roles, and population across the Bedrock fabric."
        actions={
          <>
            <Button
              className="secondary"
              onClick={() => void query.refetch()}
              disabled={query.isFetching}
            >
              <RefreshCw aria-hidden="true" />
              Refresh
            </Button>
            {canSeeEndpoint ? (
              <Button onClick={() => navigate("add-backend")}>Add backend</Button>
            ) : null}
          </>
        }
      />
      {query.isLoading ? (
        <Loading label="Checking backends" />
      ) : query.data?.backends.length ? (
        <div className="tableWrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Health</th>
                {canSeeEndpoint ? <th>Private endpoint</th> : null}
                <th>Latency</th>
                <th>Population</th>
                <th>Routing</th>
                <th>Flags</th>
              </tr>
            </thead>
            <tbody>
              {query.data.backends.map((backend) => (
                <tr key={backend.name}>
                  <td>
                    <strong>{backend.name}</strong>
                    <small>{backend.protocol}</small>
                  </td>
                  <td>
                    <Status
                      state={
                        backend.health.status === "online"
                          ? "ok"
                          : backend.health.status === "degraded" ||
                              backend.health.status === "checking"
                            ? "warning"
                            : "danger"
                      }
                    >
                      {backend.health.status}
                    </Status>
                    {backend.health.message ? <small>{backend.health.message}</small> : null}
                  </td>
                  {canSeeEndpoint ? (
                    <td className="mono">
                      {backend.host}:{backend.port}
                    </td>
                  ) : null}
                  <td>
                    {backend.health.latencyMillis >= 0 ? `${backend.health.latencyMillis} ms` : "—"}
                  </td>
                  <td>{backend.players}</td>
                  <td>{backend.default ? "Default" : backend.hub ? "Hub" : "Route"}</td>
                  <td>
                    <span className="tag">{backend.forwarding ? "Forwarded" : "Direct"}</span>
                    {backend.dropSubChunkRequests ? (
                      <span className="tag">Sub-chunks filtered</span>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Card>
          <Empty
            title="No backends configured"
            detail="An administrator can create the first secured backend route."
          />
        </Card>
      )}
    </>
  );
}
