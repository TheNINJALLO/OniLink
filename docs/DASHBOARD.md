# OniLink operations dashboard

The dashboard is built into `OniLink.jar`; there is no second service, database, or web package to install. It reads the running proxy state directly and exposes only actions OniLink can actually perform.

> [!WARNING]
> Dashboard sessions and credentials must not cross the public Internet over plain HTTP. The default listener is loopback-only. For remote administration, put it behind HTTPS, limit source addresses at the firewall, and do not share a dashboard URL publicly.

## Capabilities

| Area | Capability |
| --- | --- |
| Overview | Proxy version, uptime, player count, JVM use, listener, backend status, and RakNet probe latency |
| Players | Authenticated identity summary, route, protocol, join state, transfer, disconnect, and bounded packet trace |
| Packet Monitor | Detailed token-redacted packet flow, cross-version codec matches, review-required gaps, compiled packet catalog, and JSON captures |
| Backends | Population, routing flags, forwarding state, endpoint visibility by role, and live health |
| Allowlist | Authenticated XUID membership, connected-player enrollment, manual enrollment, and immediate removal |
| Configuration | Guided BDS backend setup, protected secret creation, redacted editor, optimistic revision check, parser validation, automatic backup, and rollback |
| Operations | Network alert, bounded log tail, redacted support bundle, and graceful shutdown |
| Security | First-run owner claim, PBKDF2 password hashes, expiring bearer sessions, roles, optional TOTP, login throttling, and append-only audit records |
| Integration | Prometheus-format `/metrics` for authenticated viewers and JSON APIs used by the bundled UI |
| Tenancy | Owner-created customer scopes, multiple isolated proxy listeners, shared login URL, and cross-tenant API enforcement |

## 1. Configure the listener

These settings are in the same `config.properties` used by OniLink:

```properties
# Enable the embedded HTTP service.
dashboard.enabled=true

# Safe standalone default: only this machine can reach the dashboard.
dashboard.host=127.0.0.1
dashboard.port=8080

# Browser sessions expire after this many minutes.
dashboard.sessionMinutes=480

# Relative paths resolve beside config.properties.
dashboard.dataDirectory=dashboard

# Request and log-tail safety limits.
dashboard.maxRequestBytes=262144
dashboard.logTailLines=400
```

Restart OniLink after changing dashboard listener settings. A successful start prints both the Bedrock UDP listener and dashboard TCP URL.

### Local-only administration

Keep `dashboard.host=127.0.0.1` and open `http://127.0.0.1:8080/` on the OniLink host. For a remote workstation, use an SSH tunnel:

```bash
ssh -L 8080:127.0.0.1:8080 onilink@proxy.example.com
```

Then open `http://127.0.0.1:8080/` locally. The dashboard remains unreachable from other network clients.

### HTTPS reverse proxy

Keep OniLink on loopback and terminate TLS at a maintained reverse proxy. Example Caddy site:

```caddyfile
onilink-admin.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Example NGINX location inside an HTTPS server block:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
}
```

Restrict the HTTPS virtual host to your VPN or administrator CIDRs. OniLink deliberately does not trust forwarded client-address headers, so its audit log records the directly connected reverse proxy address.

## 2. Create the first owner

On the first dashboard-enabled start, OniLink creates:

```text
dashboard/FIRST_RUN_SETUP.txt
```

The console also prints the dashboard URL and one-time setup code. In a browser:

1. Open the dashboard URL.
2. Copy the value after `Setup code:` from `FIRST_RUN_SETUP.txt`.
3. Choose an owner username containing 3–32 letters, numbers, periods, underscores, or hyphens.
4. Choose a unique password containing 12–256 characters.
5. Submit the form and store the password in a password manager.

The setup file is mode `0600` on POSIX systems when the filesystem supports it, and is deleted after the owner is created. The account file stores a salted PBKDF2-HMAC-SHA256 hash, never the plaintext password.

For automated provisioning, an administrator may set a random value of at least 16 characters in `ONILINK_DASHBOARD_SETUP_CODE` before the first start. Treat it as a one-time secret and remove it after setup.

## 3. Assign roles

The owner creates provider-operator accounts under **Account → Dashboard users**. Customer accounts
are created under **Tenant Hosting** so they receive a mandatory tenant scope.

| Role | Access |
| --- | --- |
| `viewer` | Overview, players, backends, packet monitor, and authenticated metrics |
| `operator` | Viewer access plus logs, alerts, transfer, disconnect, and packet trace |
| `admin` | Operator access plus endpoint details, configuration, audit, and support bundles |
| `owner` | All access plus user administration and proxy shutdown |
| `tenant` | Only the assigned tenant's **My Proxies** page and proxy-scoped operations |

OniLink permits one owner account. Give routine operators `operator`, and reserve owner access for account recovery and shutdown.

Every user can change their own password and enroll a standards-compatible TOTP authenticator under **Account**. Password or TOTP changes revoke that user's existing sessions and require a new sign-in.

## 4. Operate the proxy

### Transfer a player

Open **Players**, select **Transfer**, and choose a configured backend. OniLink rejects the action if the player is still joining or the backend is unknown. The operation is recorded in `dashboard/audit.jsonl`.

### Capture a packet trace

Select **Trace** for one player and choose 1,000–60,000 milliseconds. Tracing is deliberately bounded and uses OniLink's existing per-connection diagnostic path.

### Inspect cross-version packets

Open **Packet Monitor** to watch the real relay, compare the client/backend codec pair, and isolate
automatic matches, explicit translators, unknown packets, or definitions that require review.
Select a packet name to load its decoded source and translated target models, authenticated XUID,
player/backend endpoints, and a hex preview of the exact uncompressed incoming packet bytes. Owners
and tenants can select a tenant proxy; server-side authorization rejects access to a runtime outside
the signed-in account's scope.

The monitor retains up to 5,000 sampled records within a 64 MiB in-memory capture budget; records
disappear on restart. Authentication-bearing login and handshake bodies are never stored, and
token-shaped values found elsewhere are redacted before the record enters the ring. **Export full
capture** includes decoded content, chat, identities, addresses, and complete Base64 packet bytes,
so treat it as sensitive operational data. Normal support bundles receive a separate metadata-only
snapshot with packet contents and player identity removed. Read [Packet monitor and cross-version
matching](PACKET_MONITOR.md) before sharing a capture or using it to implement another protocol.

### Manage player access

Open **Allowlist** as an admin or owner. Add a currently connected player to copy their Xbox-authenticated XUID, or enter a known numeric XUID and optional gamertag label. Labels are for operators only; authorization always compares the signed XUID.

Before turning enforcement on, add your own account and confirm it appears in the table. Set `allowlist.enabled=true` in **Configuration**, save, and restart OniLink. Removing an entry takes effect for new joins immediately and, when `allowlist.disconnectOnRemoval=true`, also disconnects the matching live session.

### Add a BDS backend

Open the dedicated **Add Backend** page as an admin or owner. It is also linked from **Backends**
and **Configuration**. The wizard separates **Players connect to this proxy** from **OniLink
forwards them to this server**. Enter the destination BDS IP/domain and UDP port in separate fields,
then enter the proxy IP that BDS observes. The existing proxy port is shown automatically; adding a
backend does not require another proxy allocation.

On generation, OniLink:

1. Rejects stale configuration revisions and duplicate routes.
2. Generates a unique standard-Base64 secret from 32 random bytes.
3. Creates `secrets/<backend>.key` beside `config.properties` with owner-only POSIX permissions.
4. Appends the matched backend block without changing the hub, join order, or failover policy.
5. Parses the complete configuration and creates `config.properties.dashboard.bak`.
6. Shows the exact non-secret OniLink properties that were saved.
7. Returns the matching `<backend>.key` and full `onibridge.toml` for Endstone.

The result is shown once. Download both files and place them in `/home/container/plugins/onibridge/` on the new BDS server. Start Endstone, confirm the native hook is active, restart OniLink, then test `/server <backend>`.

The secret is never written into `config.properties` or the audit record. See [Adding another BDS backend](ADDING_BACKEND.md) for a worked Pterodactyl example, manual setup, routing/failover choices, and troubleshooting.

### Operate tenant proxies

Owners can open **Tenant Hosting** to create customer-scoped dashboard accounts and isolated proxy
listeners inside the existing OniLink container. Each logical proxy uses one additional UDP
allocation assigned to the same Pterodactyl server. OniLink does not create another server or egg
and does not use a Pterodactyl Application API key.

Tenants sign in at the same URL and land on **My Proxies**. They see only their tenant's listeners,
players, backends, allowlists, handoffs, and lifecycle controls. Tenant configuration and keys are
stored beneath `dashboard/tenancy/`; treat the complete `dashboard/` directory as credential
material. The field-by-field guide is [Single-container tenant hosting](TENANT_HOSTING.md).

The provider owner or a signed-in tenant can use **Choose the primary server** on **My Proxies** to
select which named backend receives new players. OniLink validates the selected route against that
proxy's backend list, saves it with an optimistic revision check, and restarts only the selected
proxy. Connected players on that listener must reconnect. This changes the initial join destination;
it does not change the separately configured `/hub` destination or another tenant's proxy.

### Edit configuration safely

Open **Configuration** as an admin or owner. The browser receives a redacted view; keys recognized as passwords, tokens, webhooks, private keys, or secrets cannot be changed or removed there.

On save, OniLink:

1. Rejects the edit if the file changed since it was loaded.
2. Restores protected values from the server-side original.
3. parses the complete proposed configuration through `ProxyConfig`.
4. Copies the current file to `config.properties.dashboard.bak`.
5. Atomically replaces `config.properties` when supported.

The running proxy is not hot-reconfigured. Review the saved file and restart OniLink. **Restore last backup** validates the backup before putting it back and keeps the replaced file as `config.properties.dashboard.pre-rollback`.

### Build a support bundle

Admins can download a ZIP containing runtime state, player/backend summaries without private addresses, redacted configuration, and bounded log/audit tails. Inspect the archive before sharing it; player names and operational events can still be sensitive.

## 5. Pterodactyl

The released egg enables the dashboard by default and maps both services to the primary allocation's number:

```text
Bedrock clients  → primary-port/UDP
Dashboard HTTP  → primary-port/TCP
```

TCP and UDP are separate transports, so OniLink can bind both on the same numeric port. The node/provider firewall must allow the needed protocol. After the first start:

1. Open **Files → dashboard → FIRST_RUN_SETUP.txt** in Pterodactyl.
2. Browse to `http://NODE-OR-DOMAIN:PRIMARY_PORT/` only from a trusted network.
3. Create the owner; the setup file disappears.
4. Put the dashboard behind an HTTPS proxy before normal remote use.
5. Persist and back up the `dashboard/` directory with `config.properties`.

Set **Enable operations dashboard** to `false` when no web listener should be exposed. The egg rewrites `dashboard.host`, `dashboard.port`, and `dashboard.enabled` at startup; change the egg variable rather than those three properties.

## 6. Files and backup

```text
dashboard/
├── accounts.properties       # password hashes, roles, and permission-protected TOTP material
├── audit.jsonl               # append-only operator activity
├── tenancy/                  # scoped proxy catalogs, runtime configs, keys, and handoffs
└── FIRST_RUN_SETUP.txt       # present only until owner setup
```

Back up `dashboard/` and `config.properties` together. Protect backups as credentials: the account file does not contain passwords, but offline access permits password-hash attacks and exposes TOTP secrets.

## 7. Frontend development and embedded builds

The production control plane is a React and strict-TypeScript application in `OniLink/dashboard-ui/`.
Node is used only while building; the deployed service remains the single Java process:

```text
java -jar OniLink.jar config.properties
```

Use Node.js 22 and the committed npm lockfile for local work:

```bash
cd OniLink/dashboard-ui
npm ci
npm run format:check
npm run lint
npm run typecheck
npm run test -- --run
npm run build
```

`npm run dev` starts Vite on port 5173 and proxies `/api`, `/health`, and `/metrics` to
`http://127.0.0.1:8080`. Start a local OniLink instance with its dashboard on that address before
testing workflows against real server state. The development server is not a production deployment.

Gradle owns the release build. `npmInstall` uses `npm.cmd` on Windows and `npm` on Linux, while
`dashboardBuild` produces content-hashed assets. `processResources` places the result beneath
`/dashboard/`, and `standaloneJar` packages it into `dist/OniLink.jar`:

```bash
cd OniLink
./gradlew test standaloneJar --no-daemon
jar tf dist/OniLink.jar | grep dashboard
```

On Windows PowerShell, inspect the JAR with:

```powershell
./gradlew.bat test standaloneJar --no-daemon
jar tf dist/OniLink.jar | Select-String dashboard
```

The embedded server sends HTML with `Cache-Control: no-store` and may cache Vite's hashed assets as
immutable. It serves only the generated index and known files below `/assets/`; unknown files,
encoded paths, traversal attempts, and arbitrary classpath resources return `404`. API, health, and
metrics routes never fall back to the application shell.

### Content Security Policy

The UI is intentionally compatible with OniLink's existing strict policy:

```text
default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self';
img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
```

Do not add inline scripts, inline styles, runtime style injection, remote fonts, CDN assets,
`unsafe-inline`, or `unsafe-eval`. API values are rendered as text; do not introduce raw-HTML
rendering for logs, configuration, names, endpoints, errors, or audit details.

### CI and release behavior

CI installs Node 22, runs the npm format, lint, type, test, and production build gates, and then runs
the Java tests. The Java and release jobs also install Node before Gradle because resource processing
builds the dashboard. The released container still needs only Java 21; npm and Node are not installed
or started by the Pterodactyl egg.

## 8. Recovery and troubleshooting

| Symptom | Check |
| --- | --- |
| Browser cannot connect | Confirm the TCP port is allowed, `dashboard.enabled=true`, and the configured host is reachable from the client |
| Bedrock works but dashboard does not | Bedrock uses UDP; separately allow or publish TCP for the same port |
| `401 Unauthorized` | Sign in again; sessions are process-local and end on restart or credential changes |
| `403 Cross-origin mutation rejected` | Use the dashboard's own URL and preserve the original `Host` through the reverse proxy |
| `409 Configuration changed on disk` | Reload the editor before saving; another process changed the file |
| Dashboard fails at startup | Check whether another TCP service owns the configured port and whether the data directory is writable |
| Dashboard HTML loads without styling | Rebuild with `npm ci` and `./gradlew processResources`; confirm the JAR contains `dashboard/assets/index-*.css` |
| Browser reports a missing hashed asset | Do not copy loose dashboard files between releases; replace the complete JAR so its HTML and hashed assets match |
| Gradle cannot find npm | Install Node.js 22 on the build machine and confirm `npm` (Linux) or `npm.cmd` (Windows) is on `PATH` |
| CSP blocks a development dependency | Remove inline or remote content; production policy is not weakened for frontend packages |

If the only owner password is lost, stop OniLink, make a protected backup, and move `dashboard/accounts.properties` out of the active path. Restart OniLink to create a new one-time setup file. This resets every dashboard account; it does not change proxy forwarding secrets or backend player data.

```bash
mv dashboard/accounts.properties dashboard/accounts.properties.locked-out-backup
java -jar OniLink.jar config.properties
```

Do not perform recovery while OniLink is running.

## 9. OniControl workspace

The **OniControl** route shows bridge health, negotiated capabilities, virtual-session totals,
scoped action history, typed packet rules, and an action/plan editor. Select an authenticated target
before loading capabilities; the server resolves the target to one XUID and connection and the UI
shows the exact backend and execution plane before confirmation. Destructive actions require the
returned single-use token and an explicit confirmation checkbox.

Rules require Admin. Commands and transport changes require Owner. A tenant receives no actions by
default: the provider owner opens **Tenant Hosting -> Tenant OniControl grants** and grants only the
reviewed Operator subset. Server-side checks apply even if a caller bypasses the UI. Tenants with a
single proxy are scoped automatically; API callers for tenants with several proxies must select a
proxy explicitly.

Protocol Lab remains disabled by default and is visible only to the provider Owner. After explicit
XUID/backend allowlists are configured, the Owner can start a timed session, schema-edit a reviewed
semantic clientbound model, dry-run its negotiated-codec encoding, and send it under a bounded rate
limit. It never accepts raw bytes or numeric packet IDs. Ordinary actions, plans, and Protocol Lab
all reject authentication material, tokens, stack IDs, memory fields, and shell commands.
