import { ArrowRight } from "lucide-react";

export function ConnectionPath({
  proxyEndpoint,
  destinationEndpoint,
}: {
  proxyEndpoint: string;
  destinationEndpoint: string;
}) {
  return (
    <div className="connectionPath" aria-label="Player connection path">
      <div>
        <span>Players connect to this proxy</span>
        <strong>{proxyEndpoint}</strong>
      </div>
      <ArrowRight aria-hidden="true" />
      <div>
        <span>OniLink forwards them to this server</span>
        <strong>{destinationEndpoint}</strong>
      </div>
    </div>
  );
}
