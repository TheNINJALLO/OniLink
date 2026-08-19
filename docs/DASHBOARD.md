# OniLink operations dashboard

The dashboard is built into `OniLink.jar`; there is no second service, database, or web package to install. It reads the running proxy state directly and exposes only actions OniLink can actually perform.

> [!WARNING]
> Dashboard sessions and credentials must not cross the public Internet over plain HTTP. The default listener is loopback-only. For remote administration, put it behind HTTPS, limit source addresses at the firewall, and do not share a dashboard URL publicly.

## Capabilities

| Area | Capability |
| --- | --- |
| Overview | Proxy version, uptime, player count, JVM use, listener, backend status, and RakNet probe latency |
| Players | Authenticated identity summary, route, protocol, join state, transfer, disconnect, and bounded packet trace |
| Backends | Population, routing flags, forwarding state, endpoint visibility by role, and live health |
| Allowlist | Authenticated XUID membership, connected-player enrollment, manual enrollment, and immediate removal |
| Configuration | Guided BDS backend setup, protected secret creation, redacted editor, optimistic revision check, parser validation, automatic backup, and rollback |
| Operations | Network alert, bounded log tail, redacted support bundle, and graceful shutdown |
| Security | First-run owner claim, PBKDF2 password hashes, expiring bearer sessions, roles, optional TOTP, login throttling, and append-only audit records |
| Integration | Prometheus-format `/metrics` for authenticated viewers and JSON APIs used by the bundled UI |

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

The owner creates additional accounts under **Account → Dashboard users**.

| Role | Access |
| --- | --- |
| `viewer` | Overview, players, backends, and authenticated metrics |
| `operator` | Viewer access plus logs, alerts, transfer, disconnect, and packet trace |
| `admin` | Operator access plus endpoint details, configuration, audit, and support bundles |
| `owner` | All access plus user administration and proxy shutdown |

OniLink permits one owner account. Give routine operators `operator`, and reserve owner access for account recovery and shutdown.

Every user can change their own password and enroll a standards-compatible TOTP authenticator under **Account**. Password or TOTP changes revoke that user's existing sessions and require a new sign-in.

## 4. Operate the proxy

### Transfer a player

Open **Players**, select **Transfer**, and choose a configured backend. OniLink rejects the action if the player is still joining or the backend is unknown. The operation is recorded in `dashboard/audit.jsonl`.

### Capture a packet trace

Select **Trace** for one player and choose 1,000–60,000 milliseconds. Tracing is deliberately bounded and uses OniLink's existing per-connection diagnostic path.

### Manage player access

Open **Allowlist** as an admin or owner. Add a currently connected player to copy their Xbox-authenticated XUID, or enter a known numeric XUID and optional gamertag label. Labels are for operators only; authorization always compares the signed XUID.

Before turning enforcement on, add your own account and confirm it appears in the table. Set `allowlist.enabled=true` in **Configuration**, save, and restart OniLink. Removing an entry takes effect for new joins immediately and, when `allowlist.disconnectOnRemoval=true`, also disconnects the matching live session.

### Add a BDS backend

Open **Configuration → Add BDS Backend** as an admin or owner. Enter a lowercase route name, the private BDS endpoint, the proxy source CIDR observed by that backend, and optional bridge/key IDs.

On generation, OniLink:

1. Rejects stale configuration revisions and duplicate routes.
2. Generates a unique standard-Base64 secret from 32 random bytes.
3. Creates `secrets/<backend>.key` beside `config.properties` with owner-only POSIX permissions.
4. Appends the matched backend block without changing the hub, join order, or failover policy.
5. Parses the complete configuration and creates `config.properties.dashboard.bak`.
6. Returns the matching `<backend>.key` and full `onibridge.toml` for Endstone.

The result is shown once. Download both files and place them in `/home/container/plugins/onibridge/` on the new BDS server. Start Endstone, confirm the native hook is active, restart OniLink, then test `/server <backend>`.

The secret is never written into `config.properties` or the audit record. See [Adding another BDS backend](ADDING_BACKEND.md) for a worked Pterodactyl example, manual setup, routing/failover choices, and troubleshooting.

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
└── FIRST_RUN_SETUP.txt       # present only until owner setup
```

Back up `dashboard/` and `config.properties` together. Protect backups as credentials: the account file does not contain passwords, but offline access permits password-hash attacks and exposes TOTP secrets.

## 7. Recovery and troubleshooting

| Symptom | Check |
| --- | --- |
| Browser cannot connect | Confirm the TCP port is allowed, `dashboard.enabled=true`, and the configured host is reachable from the client |
| Bedrock works but dashboard does not | Bedrock uses UDP; separately allow or publish TCP for the same port |
| `401 Unauthorized` | Sign in again; sessions are process-local and end on restart or credential changes |
| `403 Cross-origin mutation rejected` | Use the dashboard's own URL and preserve the original `Host` through the reverse proxy |
| `409 Configuration changed on disk` | Reload the editor before saving; another process changed the file |
| Dashboard fails at startup | Check whether another TCP service owns the configured port and whether the data directory is writable |

If the only owner password is lost, stop OniLink, make a protected backup, and move `dashboard/accounts.properties` out of the active path. Restart OniLink to create a new one-time setup file. This resets every dashboard account; it does not change proxy forwarding secrets or backend player data.

```bash
mv dashboard/accounts.properties dashboard/accounts.properties.locked-out-backup
java -jar OniLink.jar config.properties
```

Do not perform recovery while OniLink is running.
