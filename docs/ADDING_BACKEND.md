# Add another BDS backend

This guide adds a second, third, or later BDS + Endstone server to an existing OniLink network. The dedicated dashboard wizard is the recommended path: it updates OniLink, generates a unique secret, and creates the matching Endstone configuration as one operation.

For Geyser-backed Java servers, use [Geyser integration](GEYSER.md). The BDS wizard generates a native OniBridge configuration and should not be used for Geyser.

> [!IMPORTANT]
> Every backend needs its own backend name, bridge ID, and secret. Reusing a secret across servers is rejected by OniLink and weakens backend isolation.

## What you need before starting

- An OniLink dashboard account with the `admin` or `owner` role.
- A BDS `1.26.44.3` + Endstone `0.11.9` server.
- The matching Linux OniBridge `.so` already in the backend's `plugins/` directory.
- The BDS server's UDP allocation, which the OniLink container can reach.
- OniLink's public or egress IP, without its player port.

Write down these values:

| Wizard field | Example | Meaning |
| --- | --- | --- |
| Backend name | `creative` | Lowercase route name used by `/server creative` |
| BDS allocation | `198.51.100.20:25571` | IP and UDP port assigned to the BDS server |
| OniLink public IP | `198.51.100.10` | IP BDS observes for OniLink; do not include OniLink's player port |

The wizard converts the OniLink IP to an exact `/32` IPv4 or `/128` IPv6 trust rule. It also
generates the bridge ID, key ID, and secret. Existing installations performing a key rotation can
open **Advanced identity labels** to override the generated labels.

## Allocation answer

The OniLink container needs **one primary allocation**, regardless of the number of backends. Bedrock
uses that port over UDP and the dashboard uses the same number over TCP. Every BDS server keeps its
own one UDP allocation. OniBridge runs inside BDS and does not need another port.

For three BDS servers, the layout is therefore one OniLink allocation plus three BDS allocations;
only the first is assigned to the OniLink container. Two BDS servers may share one IP if their UDP
ports differ.

## Recommended: use the dashboard wizard

### 1. Open the wizard

1. Sign in to the OniLink dashboard.
2. Open **Add Backend** in the main navigation. You can also select **Add BDS backend** from the Backends page.
3. Enter the backend name, BDS `IP:port`, and OniLink IP from the worksheet above.
4. Select **Create backend setup package** once.

The operation is revision-checked and validated before it changes the live file. OniLink creates `config.properties.dashboard.bak`, preserves every existing backend, and appends the new route to `backends=`.

### 2. Download the setup package

The proxy route and its protected key are installed automatically. Download
`<backend>-onibridge-setup.zip` immediately. It contains:

- `<backend>.key` — the new 32-byte Base64 forwarding secret.
- `onibridge.toml` — a complete matched Endstone configuration.
- `INSTALL.txt` — the allocation summary, destination paths, and startup checks.

The setup ZIP is returned only by that setup response. Keep it private. The secret is never written
into `config.properties` or the audit log. Individual downloads remain under **Show individual files
and reference values** as a recovery option.

The OniLink route and its copy of the key are already installed automatically. Do not paste the displayed key into the proxy properties:

```text
/home/container/
├── config.properties
└── secrets/
    └── creative.key
```

The new route uses:

```properties
backends=survival,creative

backend.creative.host=198.51.100.20
backend.creative.port=25571
backend.creative.forwarding.enabled=true
backend.creative.forwarding.bridgeId=creative-main
backend.creative.forwarding.activeKeyId=key-1
backend.creative.forwarding.activeSecretEnv=
backend.creative.forwarding.activeSecretFile=secrets/creative.key
backend.creative.forwarding.tokenLifetimeMillis=5000
```

Do not replace `activeSecretFile` with the secret value. It is a file path.

### 3. Install the files in Pterodactyl

Extract the setup ZIP locally. Open the new BDS/Endstone server in Pterodactyl and stop it. In
**Files**, create `plugins/onibridge/` if it does not exist, then upload the generated key and TOML so
the layout is exactly:

```text
/home/container/
└── plugins/
    ├── onibridge-0.1.6-bds-1.26.44.3-linux-x86_64.so
    └── onibridge/
        ├── creative.key
        └── onibridge.toml
```

The generated TOML points to the key beside it. No Endstone startup variable is required:

```toml
bridge_id = "creative-main"
backend_name = "creative"
trusted_proxy_cidrs = ["198.51.100.10/32"]
shutdown_on_hook_failure = true
reject_direct_joins = true

[forwarding]
protocol = 2
active_key_id = "key-1"
active_secret_env = ""
active_secret_file = "creative.key"
```

The release includes the remaining identity, command, compatibility, and replay settings in the generated file. Do not shorten it to only the excerpt above.

On Linux, OniBridge automatically tightens the selected key file to owner read/write (`0600`) before reading it. If the filesystem refuses that permission change, OniBridge fails closed instead of accepting an exposed secret.

### 4. Start in the correct order

1. Start the new BDS/Endstone server.
2. Confirm its console prints:

   ```text
   OniBridge native identity hook is active for the exact reviewed profile.
   ```

3. Restart OniLink. Saving or generating configuration does not hot-reload the proxy.
4. Confirm the new backend appears on **Backends** in the dashboard.
5. Join through OniLink and run:

   ```text
   /server creative
   ```

6. Confirm the player arrives with the same name and XUID.
7. Try the BDS allocation directly and confirm the direct join is rejected.

## Choosing where players start

Adding a route makes it selectable; it does not silently change the network's hub or failover behavior.

Keep the existing hub and expose the new backend only through `/server creative`:

```properties
hubBackend=survival
```

Try Creative after Survival during initial join:

```properties
join.try=survival,creative
join.attemptsPerBackend=2
```

Use Creative as a failover destination:

```properties
failover.enabled=true
failover.fallbacks=survival,creative
backend.survival.fallback=creative
backend.creative.fallback=survival
failover.onBackendKick=auto
```

To make Creative the default hub, change both settings deliberately:

```properties
backend.name=creative
hubBackend=creative
```

Use the dashboard's raw editor for these routing-policy changes, select **Validate & save**, and restart OniLink.

## Manual setup without the dashboard

Use this path only when the dashboard is disabled or unreachable.

### 1. Generate one new secret

```bash
openssl rand -base64 32
```

Create a protected file in the OniLink container:

```text
/home/container/secrets/creative.key
```

Put only the generated Base64 value in the file. Add the proxy block shown earlier to `config.properties`, including `creative` in `backends=`.

### 2. Copy the same secret to Endstone

Create:

```text
/home/container/plugins/onibridge/creative.key
```

Put the identical Base64 value in that file. Then copy an existing full `onibridge.toml` and change all of these values together:

```toml
bridge_id = "creative-main"
backend_name = "creative"
trusted_proxy_cidrs = ["198.51.100.10/32"]

[forwarding]
active_key_id = "key-1"
active_secret_env = ""
active_secret_file = "creative.key"

[compatibility]
required_profile = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"
allow_unreviewed_profile = false
```

The secret text does not go into `active_secret_env`. That field contains an environment-variable **name**, such as `ONIBRIDGE_CREATIVE_SECRET`, and is left blank when the file method is used. Configure exactly one of `active_secret_env` and `active_secret_file`.

### 3. Validate and restart

Start the backend first, confirm the hook-active message, then restart OniLink and test `/server creative`.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `backend null` or client error | Confirm the route exists after restarting OniLink and that BDS is reachable on the configured UDP address |
| `no required production hook profile is configured` | Use the complete generated TOML and keep the exact `required_profile` value |
| `configured secret environment variable is not set` | File mode requires `active_secret_env = ""` and `active_secret_file = "creative.key"` |
| Signature, key, or token rejection | The two key files must contain the identical value; bridge ID, backend name, and key ID must also match |
| Untrusted proxy source | Replace the CIDR with the source address BDS actually observes; normally use one `/32` or `/128` |
| Plugin fails before configuration loads | Verify the exact BDS/Endstone version and use the current release `.so`; do not reuse an older native library |
| New backend is absent from `/server` | Ensure its name appears once in `backends=` and restart OniLink |

For deeper diagnostics, see [Troubleshooting](TROUBLESHOOTING.md) and [Configuration](CONFIGURATION.md).

## Removing a backend

1. Transfer active players away from the backend.
2. Remove its name from `backends=`, `join.try`, and failover lists.
3. Remove every `backend.<name>.*` property.
4. Restart OniLink and verify the route is absent.
5. After confirming rollback is not needed, remove the matching OniLink and Endstone key files.

Do not delete a key before removing its enabled route; OniLink will fail configuration or token creation for that backend.
