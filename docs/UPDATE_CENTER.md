# Update Center and network operations

Endstone remains the native server runtime. A new BDS ZIP can be staged and inspected immediately;
loading server bytes does not establish native compatibility. Protocol packages can be released
independently of the proxy, while native deployment still requires an Endstone version that works
with the exact executable.

The provider owner opens **Update Center** in the dashboard. Select a tenant and proxy to keep
artifacts, acceptance evidence, player services and maintenance history in the intended scope.
Uploads and executable package/process actions are owner-only. Existing tenant accounts cannot
upload executable packages or invoke server-manager commands.

## Server updates

1. Upload the platform's server ZIP with its declared release version. The store checks the entire
   ZIP, entry sizes and CRCs, paths, archive SHA-256, executable SHA-256 and amd64 executable format.
   Archives are streamed to disk with a 512 MiB default upload limit and 4 GiB per-scope quota.
   The version label is operator-declared; compare executable fingerprints with the acquisition lock
   using [bdsctl](BDS_ACQUISITION.md) when importing an official release.
2. Configure `updates.managedServersFile` with a copy of
   [managed-servers.example.json](../examples/managed-servers.example.json). Paths and argument arrays
   come from this operator-owned file. They cannot be supplied by HTTP requests. The start and stop
   commands must control a service manager and exit after the operation; do not use a foreground
   Bedrock process as a manager command. The example uses an existing systemd Endstone service.
   A Windows service wrapper can provide the same start/stop/health command contract.
3. Enable Continuity and configure a healthy `continuity.limboBackend`. Keep Endstone installed,
   pin its version in the managed configuration, and record EULA acceptance there only after the
   server operator accepts the applicable terms. The sample leaves acceptance false.
4. Upload a sanitized fixture ZIP and run it against the candidate. The lab decodes source bytes,
   translates them, compares exact reviewed target bytes, and decodes the target again. It checks
   join, movement, inventory, crafting, commands, packs and transfer packet categories for this
   candidate's backend release. An incorrectly labelled packet cannot satisfy another category.
5. Record the real acceptance run for this exact archive and Endstone version: startup, OniBridge
   loading, client join, movement, inventory, crafting, commands, packs, transfers and clean shutdown.
   This is explicitly labelled **operator-attested live acceptance**, separate from offline tests.
6. Run **Review candidate**. It reports codec/serializer changes and every blocking requirement.
   Confirm that review to deploy to the configured backend. A spare backend can serve as the candidate.

Maintenance confirms actual evacuation, disables admission, stops Endstone, waits for a fresh offline
probe, backs up the complete directory and verifies its file hashes, installs vendor files, starts
Endstone, waits for a fresh probe advertising the expected release, then returns players. Accepted
transfer requests are never counted as arrivals. Offline return reservations survive a proxy restart.

Worlds, plugins, operator configuration, allowlists and permissions are preserved during installation.
Snapshots have separate storage and include those files for restoration. Existing vendor files are
overlaid; obsolete files from an earlier unmanaged installation may remain. Keep the server directory
free of symlinks; the manager rejects links and special files before backup/restoration. Backups are
limited to 100 GiB and 200,000 paths and are retained for operator review.

An interrupted process or filesystem phase becomes `RECOVERY_REQUIRED`. It is not silently repeated.
**Restore server snapshot** evacuates players and restores the verified full snapshot through the same
stop/health/return workflow. If no complete snapshot exists, inspect, repair and test the backend
manually, then select **Resume after manual repair** with the job revision, recovered Bedrock version,
pinned Endstone version and repair/acceptance evidence. The manager must report healthy; a fresh
matching version probe is required before admission and reserved player returns resume. The job
becomes `RECOVERED` after confirmed returns. Another active or unresolved job on the same backend
blocks rollback and recovery. A deployment's request UUID makes deployment retries idempotent.

## Protocol packages

See [Protocol packages](PROTOCOL_PACKAGES.md). The dashboard verifies an Ed25519 signature before any
package code is loaded. Activation validates exact dependencies, duplicate classes and fixture coverage
for every changed reachable protocol pair. New connections use the new registry. Existing connections
retain their registry through subsequent backend transfers. Selecting the previous package set restores
it for new connections; an empty set restores the proxy's built-in registry plus startup addon contributions.

## Resource-pack releases

Upload resource packs, then validate a named set of artifact hashes. Validation checks manifests, pack
and module UUIDs, asset conflicts, exact dependency versions and dependency cycles. A proxy pack release
contains resource modules; behavior and script packs belong in the managed Endstone server's existing
pack deployment process. Activating a validated set replaces the proxy's pack registry atomically for
new joins. Existing pack negotiations retain a snapshot. Select an earlier release to restore it.
Clients reconnect to receive changed packs. Pack bytes and release history remain available after restart.

## Player services

Enable Connect through Platform configuration. Authenticated players use:

- `/network servers` or `/server`: open a Bedrock server selector.
- `/network join <backend>` or `/server <backend>`: enter the transfer queue.
- `/network queue cancel`: leave a waiting queue.
- `/network party create|invite <player>|accept <leader>|leave|join <backend>|chat <message>`.
- `/network friend request|accept|remove <player-or-XUID>` and `/network friend list`.
- `/network chat <message>` and `/network chatmute`.

Parties require an invitation and acceptance, support up to eight members, and reserve space for the
whole group before starting transfers. Staff see per-group failures when all members cannot arrive.
Queue configuration controls capacity, reserved slots and reserved XUIDs. The default transfer capacity
is 100 until configured. Normal backend access permissions still apply to every member. Initial joins,
hub/failover routing and forced staff moves are outside this transfer queue; account for them in the
backend's own capacity settings. Social state and chat belong to the selected proxy; shared multi-node
services below do not migrate an active party or game session between proxies.

## Multiple proxy nodes

Set `cluster.configurationFile` to an authority or node configuration from
[examples](../examples/cluster-authority.example.json). Use a distinct random secret of at least 32
characters for each node and grant that node explicit tenant/proxy scopes at the authority. Put
non-loopback coordinator traffic behind HTTPS. Requests and responses also have HMAC signatures;
timestamps, persistent nonce checks and bounded request/response sizes protect the exchange.
Keep node clocks within the request freshness window of 30 seconds; this control channel is separate
from OniForward's clock-independent backend login claims.

The authority publishes shared moderation (`banned`, `muted`, optional expiry in Unix milliseconds),
backend routing (`enabled`) and quota (`capacity`) policies. Nodes publish address-free presence and
enforce policies on their own runtime. Presence expires after 45 seconds. New group transfers need an
authority lease whenever clustering is enabled; configure a quota for every target backend.

A lost heartbeat does not prove players left the backend. Last known occupancy and uncertain leases
continue to reserve capacity during a partition. After confirming an offline node is retired, remove
its peer key and publish a `node` policy with `{"retired":true}` to reclaim its reservations. A node
with a current heartbeat cannot be retired. Capacity is bounded to 64 presence nodes, 1,000 players per
node and 4,000 visible players per scope. This is one authority with multiple proxy nodes, not a replicated
consensus service. Existing sessions continue during authority outages; new quota-dependent transfers
wait. A failed proxy requires clients to reconnect through another node.

## OniPulse

Update Center exposes per-route packet, drop and failure counters, sampled translation time and
allocation (one sample per 256 packets), JVM heap, Netty direct memory and worker/player queues.
The existing journey view supplies connection and transfer stage timing. Metrics do not include
XUIDs, packet bodies, addresses or credentials. The dashboard loads the new page separately from its
initial bundle.

Set `pulse.otlp.metricsEndpoint` to the exact OTLP HTTP JSON metrics URL, usually `/v1/metrics`.
`pulse.otlp.authorizationEnvironment` optionally names the variable containing its Authorization
header. Exports run every 30 seconds with bounded responses and a total request deadline. Collector
failures increase a counter and do not interrupt packet forwarding.

## Validation boundaries

Repository tests use real codecs, a signed test package, actual HTTP endpoints and child processes
that implement a deterministic manager contract. These checks establish the implementation's behavior.
They do not constitute a real BDS/Endstone/client acceptance run or prove arbitrary future protocols
compatible. Follow the exact-version release gates in [Adding a BDS version](ADDING_BDS_VERSION.md).
