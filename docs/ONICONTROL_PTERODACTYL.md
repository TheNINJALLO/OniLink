# OniControl on Pterodactyl

The stable OniLink egg exposes disabled-by-default control, packet-rule, virtualization, TLS-client,
and Protocol Lab variables. The admin-only control secret is not user-viewable. Reinstall/update
preserves `config.properties` and never writes a live secret into a release file.

## Allocations

| Flow | Transport | Example | Exposure |
| --- | --- | --- | --- |
| Player -> OniLink | UDP | `19130/udp` | Public |
| Dashboard -> OniLink | TCP | `19130/tcp` | Restricted HTTPS/reverse proxy |
| OniLink -> BDS | UDP | `25570/udp` | Private from proxy only |
| OniLink -> OniBridge control | TCP | `25570/tcp` or `19132/tcp` | Private from proxy only |

TCP and UDP are separate transports, so the same number may be assigned when the panel/node permits
both protocols. If it does not, allocate a dedicated control TCP number. Never expose control TCP as
a player address.

## Required matching values

`CONTROL_HOST`, `CONTROL_PORT`, `CONTROL_BRIDGE_ID`, `CONTROL_BACKEND_NAME`, `CONTROL_KEY_ID`, and
the admin-only `ONILINK_CONTROL_SECRET` must match OniBridge's `[control]` block. The control secret
must differ from `ONIBRIDGE_FORWARDING_SECRET`.

For a private address outside loopback, set `CONTROL_ALLOW_PRIVATE=true` only after adding a node
firewall rule allowing the OniLink source and a matching `trusted_proxy_cidrs` entry. HMAC protects
integrity but does not encrypt cleartext.

The Java TLS variables are present for a reviewed external TLS endpoint. The bundled native bridge
currently refuses TLS mode; use a private WireGuard/sidecar tunnel for cross-node confidentiality.

## Health checks

1. OniBridge logs the private bind address and backend after the exact profile hook is active.
2. OniLink's dashboard reports connected bridge, capability revision, latency, queue, and error.
3. `/metrics` reports bridge and request aggregates without player labels.
4. A Viewer can query capabilities for an online target; an unsupported action remains hidden.

Connection refused means listener/allocation/firewall. Source rejected means CIDR/NAT mismatch.
Signature/key/bridge/backend errors mean the two matching blocks differ. A missing capability means
do not execute the action; confirm BDS/Endstone/profile versions and bridge logs.
