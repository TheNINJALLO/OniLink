# Pterodactyl deployment

Use a separate Pterodactyl server for OniLink and for every BDS or Geyser backend. The proxy allocation is public; backend allocations are private or firewalled to the proxy.

> [!NOTE]
> The primary allocation carries the provider proxy over UDP and the shared dashboard over TCP.
> Each tenant proxy needs one additional UDP allocation assigned to this same OniLink server.
> Adding a backend route to an existing proxy does not add another OniLink allocation.

To provide customer access, use **Tenant Setup** in the existing OniLink control plane. Customers
sign in at the same URL and see only their own proxies. OniLink does not create more Pterodactyl
servers or eggs and does not need an Application API key. Follow
[Single-container tenant hosting](TENANT_HOSTING.md).

## Import the OniLink egg

Download `egg-onilink.json` from the [current release](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.1.6) or [`packaging/pterodactyl`](../packaging/pterodactyl/README.md).

1. Open **Admin Panel → Nests**.
2. Select or create a nest and choose **Import Egg**.
3. Upload `egg-onilink.json`.
4. Create a server using **OniLink Bedrock Proxy** and the Java 21 image.
5. Assign one public allocation. The egg writes its primary port to the Bedrock UDP listener and dashboard TCP listener.
6. Set **Default backend host** and **Default backend UDP port** to the private address reachable from the OniLink container.
7. Leave **Enable authenticated XUID allowlist** off for the first join.
8. Generate a forwarding secret with `openssl rand -base64 32` and enter it in the admin-only **Default OniForward secret** variable.
9. Put the identical value in the default backend validator.
10. Start the backend and confirm its validator first; then start OniLink.
11. Open **Files → dashboard → FIRST_RUN_SETUP.txt**, copy the one-time code, and browse to `http://NODE-OR-DOMAIN:PRIMARY_PORT/` from a trusted network to create the dashboard owner.

The installer downloads the exact `ONILINK_VERSION` bootstrap tag, verifies `OniLink.jar`, `start-onilink.sh`, and the configuration template against that release's `SHA256SUMS`, and preserves an existing `config.properties` during reinstall. Every later container start queries GitHub's **latest stable release**, verifies the JAR, updater, and reference configuration, then updates changed runtime files atomically. Drafts and prereleases are ignored.

If GitHub is unavailable or verification fails, startup keeps the currently installed JAR. A successful replacement retains the prior version as `OniLink.jar.previous`, preserves active `config.properties`, updates only `onilink.properties.example`, and records the active release tag in `.onilink-version`. A changed updater is saved as `start-onilink.sh.previous` and takes over on the next restart.

Existing updater-enabled containers discover the newest stable release and replace `OniLink.jar` on restart. No egg reimport is required for the single-container tenant dashboard because its accounts, catalogs, and proxy supervisor are part of the JAR. Runtime updates do not rewrite Pterodactyl's stored egg definition; reimport the egg only when you need newly added panel variables or install-script changes.

### Enable the authenticated allowlist

1. Join once while **Enable authenticated XUID allowlist** is `false`.
2. In **Dashboard → Allowlist**, select your connected account and add it. Alternatively run `allowlist add <gamertag>` in the Pterodactyl console while the player is connected, or `allowlist add <XUID> <label>` at any time.
3. Confirm the entry with `allowlist list`.
4. Stop OniLink, set **Enable authenticated XUID allowlist** to `true`, and start it again.
5. Add every additional player by authenticated XUID. Removing a player takes effect immediately by default.

The egg maps the panel switch to `allowlist.enabled`. Entries persist in `/home/container/allowlist.properties`; back up that file with `config.properties` and `dashboard/`. Keep BDS `online-mode=false` and `allow-list=false` because OniLink now performs the Xbox-authenticated access check before forwarding.

The egg enables OniLink's embedded dashboard by default. Bedrock uses `PRIMARY_PORT/UDP`; the dashboard uses the same number over TCP. They can coexist because TCP and UDP are separate transports. Ensure the Wings mapping and node/provider firewall allow both protocols. The dashboard is HTTP, so restrict initial setup to a trusted network and put normal remote access behind HTTPS. See [Dashboard](DASHBOARD.md) for reverse-proxy and account examples.

The egg covers only the OniLink proxy process. It cannot redistribute BDS. Use an existing licensed BDS/Endstone server or egg for the native backend, and Geyser's official standalone egg or an existing Geyser server for the Java path.

The BDS container image must also satisfy the native OniBridge runtime. The current Linux production artifact is built on Ubuntu 22.04 and cannot import symbols newer than `GLIBC_2.35`. The current amd64 `ghcr.io/parkervcp/yolks:python_3.14` image is Debian Bookworm-based and meets that floor; verify mutable image tags with `ldd --version` after an image update. The OniLink-only egg still uses its Java 21 image. Do not copy a host `libc.so.6` into either container.

## Recommended server layout

| Panel server | Example allocation | Public? | Persistent data |
| --- | --- | --- | --- |
| OniLink | `19132/udp` + `19132/tcp` | Bedrock public; dashboard restricted | `config.properties`, `cache/`, `dashboard/`, `logs/`, packs |
| Survival BDS | `19133/udp` | No | Worlds, BDS config, Endstone plugins/data |
| Geyser | `19134/udp` | No | Geyser/Floodgate config, extensions/data |

Do not assign the backend allocation as a public/player address. If the panel cannot create a truly private allocation, enforce the source boundary at the node/provider firewall.

## Administrator-only secret variables

Generate one different Base64 secret per backend. Add it as a non-user-viewable environment variable to exactly two Pterodactyl servers: OniLink and that backend. Panel administrators can still access server variables, so restrict panel administration and protect panel/database backups.

| Backend | OniLink variable | Backend variable |
| --- | --- | --- |
| Survival BDS | `ONIBRIDGE_SURVIVAL_SECRET` | `ONIBRIDGE_SURVIVAL_SECRET` |
| Geyser/Java | `ONIBRIDGE_JAVA_SECRET` | `ONIBRIDGE_JAVA_SECRET` |

The names and values must match within each row. Values must differ between rows.

Do not bake secrets into an egg, image, startup command, Git repository, or public variable default.

The released egg declares these without values:

| Egg variable | Use |
| --- | --- |
| `ONIBRIDGE_FORWARDING_SECRET` | Required by the shipped one-backend template |
| `ONIBRIDGE_SURVIVAL_SECRET` | Optional named variable for the mixed example |
| `ONIBRIDGE_JAVA_SECRET` | Optional named variable for the mixed example |

When using the mixed example, the survival and Java values must be different. An egg export contains only blank defaults; never add a real value to `egg-onilink.json`.

### Easier file-based setup for additional BDS servers

You do not need to add or edit Endstone egg variables for a second native BDS server. In the OniLink dashboard, open the dedicated **Add Backend** page. The wizard creates a unique key under OniLink's `secrets/` directory, appends the validated route, shows the saved proxy properties, and downloads the matching key plus complete `onibridge.toml` for the Endstone container.

Upload both downloads to:

```text
/home/container/plugins/onibridge/
├── <backend>.key
└── onibridge.toml
```

The generated configuration uses `active_secret_file`, so `active_secret_env` remains empty and no panel startup variable is required. The current Linux plugin changes the selected key file to owner-only (`0600`) before reading it. Start Endstone first, confirm its native hook is active, then restart OniLink.

See [Adding another BDS backend](ADDING_BACKEND.md) for the complete field-by-field workflow and worked routing examples.

## OniLink server files

Place these in the OniLink container root:

```text
/home/container/
├── OniLink.jar
├── config.properties
├── cache/
├── dashboard/
├── logs/
└── resource-packs/        # optional
```

Manual startup command without automatic updates:

```bash
java -jar OniLink.jar config.properties
```

The egg starts through the released updater, which launches the equivalent memory-aware command after its release check:

```bash
bash ./start-onilink.sh
```

Relevant configuration:

```properties
listener.host=0.0.0.0
listener.port=19132
publicAddress=play.example.com:19132

dashboard.enabled=true
dashboard.host=0.0.0.0
dashboard.port=19132
dashboard.sessionMinutes=480
dashboard.dataDirectory=dashboard

backend.name=survival
backends=survival,java
hubBackend=survival

backend.survival.host=10.10.0.20
backend.survival.port=19133
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET

backend.java.host=10.10.0.30
backend.java.port=19134
backend.java.dropSubChunkRequests=true
backend.java.forwarding.enabled=true
backend.java.forwarding.bridgeId=java-main
backend.java.forwarding.activeKeyId=key-2026-01
backend.java.forwarding.activeSecretEnv=ONIBRIDGE_JAVA_SECRET
```

Use the actual private addresses/routes reachable between Wings containers. A panel allocation address is not always the same address another container can reach.

## Dashboard setup and persistence

The egg exposes **Enable operations dashboard** as `DASHBOARD_ENABLED`. It rewrites these properties on each start:

```properties
dashboard.enabled=true
dashboard.host=0.0.0.0
dashboard.port=PRIMARY_PORT
```

Use the egg variable to disable the dashboard; direct edits to these three fields are overwritten by Pterodactyl. Other dashboard settings remain operator-controlled.

First-run owner setup:

1. Start OniLink and wait for `OniLink dashboard listening on` in the console.
2. Open `dashboard/FIRST_RUN_SETUP.txt` in the panel file manager.
3. Copy only the value after `Setup code:`.
4. Browse to the node/domain and primary port over TCP.
5. Create the owner with a unique 12-character-or-longer password.
6. Confirm `FIRST_RUN_SETUP.txt` disappears.
7. Enroll TOTP under **Account** and create lower-privilege operator accounts for daily use.

Persist and back up `dashboard/`. It contains password hashes, roles, TOTP secrets, and audit events. Do not publish it or include it in an egg export. If your provider does not publish TCP for the primary allocation, ask the panel administrator to expose it or disable the dashboard; Bedrock continuing to work proves only the UDP mapping.

## Native BDS server files

The relevant layout is:

```text
/home/container/
├── bedrock_server
├── plugins/
│   ├── onibridge-0.1.6-bds-1.26.44.3-linux-x86_64.so
│   └── onibridge/
│       ├── survival.key          # when using the dashboard/file method
│       └── onibridge.toml
└── worlds/
```

For the original environment-variable method, the panel startup process must inherit `ONIBRIDGE_SURVIVAL_SECRET`. The TOML contains the variable name, not the secret value:

```toml
bridge_id = "survival-main"
backend_name = "survival"
trusted_proxy_cidrs = ["10.10.0.10/32"]

[forwarding]
active_key_id = "key-2026-01"
active_secret_env = "ONIBRIDGE_SURVIVAL_SECRET"

[compatibility]
required_profile = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"
allow_unreviewed_profile = false
allow_unknown_bds = false
allow_unknown_endstone = false
```

Use the complete TOML from [`examples/single-bds/onibridge.toml`](../examples/single-bds/onibridge.toml), not only this excerpt.

For the dashboard-generated file method, use the downloaded TOML unchanged:

```toml
[forwarding]
active_key_id = "key-1"
active_secret_env = ""
active_secret_file = "survival.key"
```

Configure exactly one source. A non-empty `active_secret_env` plus a non-empty `active_secret_file` is rejected.

## Geyser server files

```text
/home/container/
└── extensions/
    ├── OniBridge-Geyser.jar
    └── onibridge-geyser/
        └── config.properties
```

The Geyser server must inherit `ONIBRIDGE_JAVA_SECRET`. Bind the Geyser Bedrock listener to the private allocation and restrict it to OniLink.

## Determining `trusted_proxy_cidrs`

Pterodactyl/Wings may present traffic from a Docker bridge, node address, NAT gateway, or proxy-container address. Determine what the backend actually observes. Start with the narrowest stable address:

- One IPv4 source: `/32`
- One IPv6 source: `/128`
- Stable dedicated container subnet: the smallest exact subnet only when individual addresses cannot be stable

Do not copy a public player CIDR or trust the whole provider/node network.

## Startup order

1. Start the BDS or Geyser backend.
2. Confirm the validator loads and its configuration succeeds.
3. For native BDS, confirm the exact hook is active.
4. Start OniLink.
5. Test a client through the proxy allocation.
6. Verify the backend allocation rejects/directly blocks an unproxied client.

A backend that shuts down on a profile failure must remain offline until the exact incompatibility is corrected.

## Persistence and backups

Persist OniLink configuration/cache/logs and all backend world/plugin data. Do not persist temporary BDS analysis caches into runtime release storage. Back up both configuration sides together so key IDs and bridge/backend names remain aligned.

## EULA and profile status

Do not put `MINECRAFT_EULA_ACCEPTED` in a public egg or repository. It is needed only in controlled BDS acquisition/profile tooling, not normal forwarding runtime.

The current Linux `1.26.44.3` profile is production-approved and must use `allow_unreviewed_profile=false`. Windows and any newly generated profiles retain their own evidence gates and must not inherit this approval.
