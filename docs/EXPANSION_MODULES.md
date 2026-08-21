# OniLink 0.3 Operations Modules

OniLink 0.3 adds one shared, tenant-aware operations platform to the existing proxy and dashboard.
It does not start BDS processes, replace OniForward, or move a live RakNet session between proxy
processes. Every write is authorized by the existing dashboard session, scoped to one tenant and
proxy, schema checked, revision checked, and written to the local SQLite platform database.

## Safe rollout

Only OniPulse journey summaries and OniPack scanning are enabled by default. Begin with the
following minimal block, restart OniLink, and confirm **Platform -> Modules** before enabling an
operational module:

```properties
modules.control.enabled=false
modules.flow.enabled=false
modules.continuity.enabled=false
modules.sentinel.enabled=false
modules.pulse.enabled=true
modules.fleet.enabled=false
modules.connect.enabled=false
modules.packs.scanner.enabled=true
modules.notifications.enabled=false
```

| Module | Default | Minimum dashboard role | Failure behavior |
| --- | --- | --- | --- |
| Shared Platform | On | Viewer | A failed optional module is isolated and reported unhealthy. |
| OniControl Core | Off | Viewer; Owner for Protocol Lab | The Bedrock relay continues if the control link fails. |
| OniFlow | Off | Admin to define, Operator to run | A failed step follows its declared policy; arbitrary scripts and URLs are rejected. |
| OniContinuity | Off | Admin | A partial drain remains recorded and reports each failed player. |
| OniSentinel | Off | Admin | A missing/unhealthy quarantine backend rejects the assignment. |
| OniPulse | On | Viewer | Bounded trace history can be lost without affecting a connection. |
| OniForge | On | Viewer | Reports stay evidence-based; missing translators remain unsupported. |
| OniFleet | Off | Admin; Owner to promote/rollback | Optimistic conflicts fail without silently replacing another operator's change. |
| OniConnect | Off | Viewer/Admin by operation | Backend role synchronization reports `UNSUPPORTED` when no reviewed bridge action exists. |
| OniPack Scanner | On | Admin | Scans never activate packs or reload the live catalog. |
| Notifications | Off | Signed-in user; Admin test | Push failure never blocks the event producer or proxy. |

All retained queues and histories are bounded. Platform records live in
`dashboard/platform/onilink-platform.db`; back up the entire `dashboard/` directory while OniLink is
stopped. On POSIX systems the database is owner read/write. Windows uses the inherited directory
ACL.

## Limbo and manual drain

Create a dedicated, already-running BDS backend named `limbo`. Do not make it the primary or hub
backend. Then enable continuity:

```properties
modules.continuity.enabled=true
continuity.limboBackend=limbo
continuity.maxReservations=10000
continuity.drainTimeoutSeconds=120
continuity.returnTimeoutSeconds=120
```

The configured limbo name is reserved from ordinary joins. A drain performs this sequence:

1. Atomically mark the selected backend as draining so new joins stop.
2. Record a tenant/proxy-scoped return reservation for every connected player on it.
3. Transfer each player to the healthy limbo backend.
4. Report success, partial success, or failure without discarding failed reservations.

After maintenance, verify the backend's health in **Platform -> Continuity**, then choose **Return
players**. OniLink clears draining, returns only eligible connected reservations, expires stale
entries, and reports partial failures. It does not hide the normal Bedrock loading UI or claim a
cross-process live-session migration.

## Quarantine

Create another isolated BDS backend and keep it separate from limbo:

```properties
modules.sentinel.enabled=true
sentinel.quarantineBackend=quarantine
sentinel.autoQuarantine=false
```

An Admin assigns an authenticated XUID, reason, and optional ISO-8601 expiry in **Platform ->
Quarantine**. OniLink verifies that the backend exists and is healthy, stores the assignment, and
moves an online player immediately. The immutable join-routing snapshot gives quarantine priority
over canary, forced-host, promoted, and default routes. Expiration is enforced in that snapshot
without database I/O on the packet thread. Releasing the XUID removes the override.

Quarantine reasons and raw XUIDs are operationally sensitive. Tenant users see only their own proxy
scope; do not share database or audit exports publicly.

## OniFlow

Enable the workflow engine:

```properties
modules.flow.enabled=true
flow.maxWorkflows=500
flow.maxSteps=100
flow.maxParallelBranches=8
flow.maxExecutionSeconds=3600
flow.maxConcurrentExecutions=32
```

Workflows are JSON documents composed only from actions listed by `GET /api/modules`. Unknown
actions, input fields, roles, scopes, and destructive steps without confirmation fail closed.

```json
{
  "name": "Drain survival with approval",
  "enabled": true,
  "trigger": "MANUAL",
  "failurePolicy": "STOP_ON_FAILURE",
  "steps": [
    {"type": "APPROVAL", "label": "Confirm the maintenance window"},
    {
      "type": "ACTION",
      "action": "SET_BACKEND_DRAINING",
      "input": {"backend": "survival", "_confirmed": true}
    },
    {"type": "DELAY", "milliseconds": 5000},
    {
      "type": "ACTION",
      "action": "REQUEST_PUSH_NOTIFICATION",
      "input": {
        "user": "owner",
        "topic": "DRAIN_FAILED",
        "summary": "Maintenance drain was started",
        "route": "/#/platform/continuity"
      }
    }
  ]
}
```

Supported triggers are manual, typed internal event, internal webhook, interval schedule, and one
ISO-8601 scheduled instant. Schedules are generic; this is not a separate scheduled-maintenance
product. `SEQUENCE` is ordered, `PARALLEL` is bounded, conditions use declared data rather than a
script language, delays remain cancellable, and an approval pauses a frozen workflow revision.

## Dynamic backends, canaries, and blue-green

Enable OniFleet after OniPulse:

```properties
modules.fleet.enabled=true
fleet.maxDynamicBackends=1000
fleet.canaryAssignmentMinutes=120
fleet.maxCanaryPercentage=25
```

### Register a backend

In **Platform -> Fleet**, supply:

- **Backend name**: the routing name, for example `survival-green`.
- **Host/port**: the private BDS address OniLink forwards to, not the public proxy listener.
- **Protocol**: `auto` unless the backend was deliberately pinned and tested.
- **Proxy ID**: the one listener/tenant proxy that owns this backend.
- **Bridge ID/key ID**: the matching OniBridge `[control]` identities.
- **Secret environment** or protected file: a reference only. Never enter the secret value into the
  backend name, bridge ID, or dashboard JSON.

Registration and updates use both the live registry revision and saved record revision. If another
operator wins the race, refresh and review their change. **Validate** reports configuration, RakNet
health, the protocol/version advertised by the BDS pong, OniControl connection state, and capability
revision. **Disable new joins** preserves the definition. **Roll back definition** is Owner-only,
requires confirmation, and restores the newest saved pre-update/pre-removal revision. Secret
references are never returned by ordinary APIs.

### Canary routing

Assign an exact test XUID, candidate backend, percentage, and optional global-role eligibility. Test
accounts are always eligible; other assignments use a deterministic tenant/XUID bucket, so they do
not randomly move during a session. Assignments expire after the configured sticky window. Limbo,
quarantine, and currently quarantined players are excluded. Operators can opt a player out, stop all
canary routes immediately, and record a result with an optional support-safe journey reference.
The Fleet view exposes canary and normal outcomes for comparison.

### Blue-green deployment

A deployment definition contains `blueBackend`, `greenBackend`, the candidate revision, health
gates, canary policy, and these optional promotion gates:

```json
{
  "id": "survival-2026-08",
  "name": "Survival August update",
  "blueBackend": "survival-blue",
  "greenBackend": "survival-green",
  "candidateRevision": 3,
  "expectedProtocol": "844",
  "expectedBridgeId": "survival-green-bridge",
  "expectedCapabilityRevision": 1,
  "blockingCompatibility": false,
  "humanApprovalRequired": true,
  "humanApproved": true,
  "unresolvedDrainFailure": false,
  "canaryPolicy": {"percentage": 10, "stickyMinutes": 120}
}
```

Use `REGISTER_GREEN`, `VALIDATE_GREEN`, `START_CANARY`, `STOP_CANARY`, `PROMOTE_GREEN`,
`DRAIN_BLUE`, `ROLLBACK_TO_BLUE`, and finally `RETIRE_BLUE`. Promotion checks live health, expected
protocol, bridge identity, capability revision, compatibility blocks, approval, and drain failures.
Blue remains registered until explicit retirement. Rollback re-enables and undrains blue before
routing new joins back to it. OniLink integrates already-running backends; it never creates a BDS
process.

## Presence, global roles, and support

```properties
modules.connect.enabled=true
presence.expirationSeconds=60
support.maxOpenTicketsPerPlayer=5
support.ticketRateLimitMinutes=10
```

Presence stores the authenticated XUID boundary, display label, proxy/backend, connection age,
transfer/quarantine state, activity time, and visibility. Tenant scope is mandatory. Non-operator
views receive a stable pseudonym instead of a raw XUID; addresses are never included. Local presence
expires after disconnect/timeout, and the provider boundary can be replaced later without changing
the API. This release does not claim distributed multi-node presence.

Global roles contain a name, permissions, and optional parent. Parent existence and full hierarchy
cycles are rejected on save. Assignments use authenticated XUIDs, optional ISO-8601 expiry, and
optimistic revisions. Effective-permission preview walks the hierarchy while ignoring expired
assignments. Backend synchronization is reported as `UNSUPPORTED` because the approved initial
OniControl actions do not safely replace a backend-native permission system.

Players use:

```text
/onilink support create I cannot open my inventory
/onilink support status
/onilink support status <ticket-id>
/onilink support reply <ticket-id> It happened again after reconnecting
```

Persistence runs asynchronously outside the packet handler. Players can read and reply only to
their own tickets. Staff see only authorized tenant scope. Creation and replies are rate limited;
messages are bounded; status transitions are validated; and high-priority creation can request a
push. No packet body, address, token, or secret is attached automatically.

## Pack Conflict Scanner

The scanner accepts configured pack directories, a staging directory, individual `.mcpack`/`.zip`
archives, and bounded authenticated uploads. Limits are:

```properties
modules.packs.scanner.enabled=true
packs.scanner.maxArchiveBytes=268435456
packs.scanner.maxEntries=10000
packs.scanner.maxExpandedBytes=1073741824
```

It checks ZIP integrity, traversal, unsafe links/entries, archive and expansion limits, compression
ratio, manifests, semantic versions, pack/module UUIDs, dependencies/cycles, asset conflicts,
history fingerprints, content changes without a version increase, incomplete uploads, executable
content, and exposed world-pack references. Results include severity, rule ID, path, correction,
fingerprint, dependency graph, conflict groups, and pass/fail. Archives are streamed and are never
extracted into the live pack tree. A scan never installs, activates, reloads, restarts, or reconnects.

## Install the iOS PWA

OniMobile is the existing dashboard made installable; it is not a second server.

1. Put the dashboard behind HTTPS with its existing CSP intact.
2. Open it in Safari on iPhone or iPad and sign in normally.
3. Choose **Share -> Add to Home Screen**.
4. Open the installed OniLink icon and confirm the connection indicator and read-only cached module
   health.

The service worker caches only the application shell and an explicitly safe health summary. It
never caches `/api`, `/metrics`, secrets, TOTP, support messages, packet evidence, audits, or control
signatures. Logout and credential invalidation clear private caches. There are no inline scripts,
inline styles, remote fonts, CDNs, `unsafe-inline`, or `unsafe-eval` additions.

## Web Push with VAPID

Generate a Web Push P-256 VAPID keypair using a trusted standards-compliant tool. Put the public key
in configuration and the private key only in the named environment variable:

```properties
modules.notifications.enabled=true
notifications.vapidPublicKey=<URL-safe-public-key>
notifications.vapidPrivateKeyEnvironment=ONILINK_VAPID_PRIVATE_KEY
notifications.vapidSubject=mailto:admin@example.com
notifications.maxSubscriptionsPerUser=10
```

Set `ONILINK_VAPID_PRIVATE_KEY` in the container environment, restart, then use **Platform ->
Notifications** to register a named device and topics. Subscriptions are per dashboard user and
tenant/proxy. Users can revoke a device or send an authorized test. HTTP 404/410 responses remove
expired subscriptions. Delivery runs on a bounded worker with per-user/topic rate limits.

Push payloads contain only `title`, a topic, a short redacted summary, and an authenticated dashboard
route. XUIDs, IP endpoints, tokens, secrets, and full support text are removed. Supported initial
topics are `BACKEND_UNHEALTHY`, `CONTROL_BRIDGE_DISCONNECTED`, `DRAIN_FAILED`, `CANARY_FAILED`,
`GREEN_READY_FOR_PROMOTION`, `ROLLBACK_COMPLETED`, `PLAYER_QUARANTINED`,
`HIGH_PRIORITY_SUPPORT_TICKET`, `PACK_SCAN_FAILED`, and `WORKFLOW_APPROVAL_REQUIRED`.

## Protocol Lab restrictions

Protocol Lab additionally requires the main OniControl gates and explicit test allowlists:

```properties
control.enabled=true
modules.control.enabled=true
protocolLab.enabled=true
protocolLab.allowBackendBound=false
protocolLab.maxPacketsPerMinute=30
protocolLab.maxSessionSeconds=300
protocolLab.allowedXuids=1000000000000001
protocolLab.allowedBackends=survival-test
```

Only the provider Owner can start a timed session. Select the connected allowlisted player, its
current allowlisted backend, and one schema model. Validation constructs a semantic packet and dry
encodes it with that player's negotiated codec. Sending consumes the rate limit and records
`PROTOCOL_LAB` in the packet monitor. Current reviewed models are system message, title, subtitle,
actionbar, toast, play/stop sound, and particle. Backend-bound models remain unavailable until one
completes semantic review.

Login/sub-client login, JWT chains, encryption handshakes, authentication, OniForward, token-bearing
fields, numeric packet IDs, raw bytes, and unknown models are hard denied. Protocol Lab does not run
packet rules recursively.

## Build and verification

Run the same local gates as CI:

```bash
cd OniLink
./gradlew clean check standaloneJar --no-daemon

cd dashboard-ui
npm ci
npm run format:check
npm run lint
npm run typecheck
npm run test -- --run
npm run build

cd ../../../OniBridge
cmake -S . -B build/ci -DONIBRIDGE_BUILD_PLUGIN=OFF -DONIBRIDGE_BUILD_TESTS=ON
cmake --build build/ci --config Release
ctest --test-dir build/ci -C Release --output-on-failure
```

`forgeReports` generates the machine-readable and Markdown compatibility evidence beneath
`OniLink/build/forge/`. Linux release CI separately builds the exact profile plugin with LLVM 18,
checks the `GLIBC_2.35` ceiling and C++ runtime policy, scans archives for forbidden BDS content, and
packages checksums. BDS executables and licensed client files are never committed or released.
