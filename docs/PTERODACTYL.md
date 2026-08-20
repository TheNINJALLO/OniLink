# Pterodactyl setup

Run OniLink in one Pterodactyl server and each BDS/Endstone backend in its own server. OniLink's
player allocation is public; backend allocations must be private or firewalled to OniLink.

## Import the egg

Download `egg-onilink.json` from the stable
[`v0.2.0` release](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.2.0), then open:

```text
Admin Panel → Nests → Import Egg
```

Create an OniLink server from the imported egg. Assign:

- one public UDP allocation for players;
- one TCP allocation for the dashboard if you publish it through a protected route;
- one extra UDP allocation on this same server for every tenant proxy listener you create.

TCP and UDP may use the same numeric port because they are different protocols. A normal
single-proxy installation does not need a separate port per backend: OniLink initiates connections
to each backend's own private allocation.

## Automatic updates

The egg bootstraps the selected `ONILINK_VERSION`, verifies `OniLink.jar`, `start-onilink.sh`, and the
reference configuration against `SHA256SUMS`, and preserves an existing `config.properties` during
reinstall. Every container start checks **Automatic update channel** and atomically installs only
checksum-valid changes.

| Channel | Behavior |
| --- | --- |
| `stable` | Newest published non-prerelease; default for `v0.2.0` |
| `beta` | Newest published release including prereleases |
| `pinned` | Stays on the exact `ONILINK_VERSION` |

If an update fails, the existing JAR starts and the log explains the failure. A successful update
keeps previous runtime files for rollback. To add the updater to an older egg, back up the server,
reimport the current egg, verify its variables, and run **Reinstall Server** once. Reinstall preserves
the live proxy configuration and dashboard data but always take a backup first.

## Understand the ports

Use labels that describe direction, not implementation jargon:

| Field | Example | Meaning |
| --- | --- | --- |
| Player-facing proxy IP | `45.143.196.108` | Public address players type into Minecraft |
| Player-facing proxy port | `19130/udp` | Public OniLink listener |
| Dashboard port | `19135/tcp` | Browser control plane; protect with HTTPS/access rules |
| Destination BDS IP | `45.143.196.160` | Address OniLink uses to reach the backend |
| Destination BDS port | `25570/udp` | BDS/Endstone allocation receiving forwarded sessions |

The backend port is assigned to the backend server, not to the OniLink server. For one normal proxy
plus one dashboard, OniLink therefore needs two allocations: one UDP and one TCP. Add OniLink UDP
allocations only for tenant listeners hosted in the same container.

## Required OniLink variables

| Variable | Example | Guidance |
| --- | --- | --- |
| `ONILINK_VERSION` | `v0.2.0` | Bootstrap/pinned release tag |
| `ONILINK_UPDATE_CHANNEL` | `stable` | Normal production channel |
| `SERVER_JARFILE` | `OniLink.jar` | Leave at default |
| `CONFIG_FILE` | `config.properties` | Leave at default unless deliberately renamed |
| `BACKEND_HOST` | `45.143.196.160` | Initial destination BDS address |
| `BACKEND_PORT` | `25570` | Initial destination BDS UDP port |
| `DASHBOARD_ENABLED` | `true` | Starts the control plane |
| `ONILINK_DASHBOARD_SETUP_CODE` | empty | Optional admin-only first-run override |
| `ALLOWLIST_ENABLED` | `false` | Enable after at least one XUID is stored |
| `ONIBRIDGE_FORWARDING_SECRET` | generated Base64 | Admin-only secret shared with the default backend |

Secret variables are admin-only. Do not make them user-viewable, paste them into `config.properties`,
or include them in exported eggs.

## Configure the default route

Open **Files → config.properties** in the OniLink server. A complete example for the addresses above:

```properties
listener.host=0.0.0.0
listener.port=19130
publicAddress=45.143.196.108:19130

dashboard.enabled=true
dashboard.host=0.0.0.0
dashboard.port=19135
dashboard.dataDirectory=dashboard

backend.name=survival
backend.host=45.143.196.160
backend.port=25570
backends=survival
hubBackend=survival
backend.survival.host=45.143.196.160
backend.survival.port=25570
backend.protocol=auto

forwarding.proxyId=edge-1
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_FORWARDING_SECRET
backend.survival.forwarding.tokenLifetimeMillis=5000
```

`activeSecretEnv` contains the variable's name. Keep the line exactly as shown and put the actual
Base64 secret in the panel variable `ONIBRIDGE_FORWARDING_SECRET`.

The egg may create an initial configuration from its variables. It never overwrites an existing
configuration on reboot or reinstall. Compare your file with `onilink.properties.example` after an
upgrade to discover new settings.

## Configure the BDS/Endstone server

Place the release `.so` and matching profile JSON in the backend's Endstone `plugins/` directory.
OniBridge's configuration is normally:

```text
/home/container/plugins/onibridge/onibridge.toml
```

Use:

```toml
bridge_id = "survival-main"
backend_name = "survival"
trusted_proxy_cidrs = ["45.143.196.108/32"]
shutdown_on_hook_failure = true
reject_direct_joins = true

[forwarding]
protocol = 2
active_key_id = "key-2026-01"
active_secret_env = "ONIBRIDGE_FORWARDING_SECRET"
active_secret_file = ""
previous_key_id = ""
previous_secret_env = ""
previous_secret_file = ""
maximum_token_size = 4096
maximum_lifetime_ms = 10000
allowed_clock_skew_ms = 2000
replay_cache_max_entries = 10000

[identity]
uuid_mode = "preserve_backend"
verify_post_login_xuid = true
store_verified_identities = true

[commands]
register_native_commands = true
command_namespace = "onibridge"
interfere_with_backend_commands = false

[compatibility]
required_profile = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"
allow_unreviewed_profile = false
allow_unknown_bds = false
allow_unknown_endstone = false

[legacy_verification]
enabled = false
```

The BDS server must receive a protected `ONIBRIDGE_FORWARDING_SECRET` panel variable with exactly
the same Base64 value as OniLink. If its egg does not expose a suitable protected variable, use the
dashboard **Add Backend** setup ZIP and its restricted key file instead. The generated TOML points to
that file, so the server owner does not need shell access to repair permissions manually.

The proxy address seen from a container network may differ from the public IP. If OniBridge rejects
the source, use the source address shown in its log and narrow `trusted_proxy_cidrs` to that address.

## Add more backends from the dashboard

Open **Dashboard → Add Backend** as the owner or an administrator. The form asks in plain language:

1. the route name players/admins will recognize;
2. the IP or hostname of the destination BDS server;
3. the UDP port assigned to that BDS server;
4. the proxy source address that destination will trust.

The wizard generates the bridge ID, key ID, and a unique secret. It updates OniLink and gives you a
setup ZIP with the matching `onibridge.toml`, restricted key file, profile/install notes, and route
summary. Install that ZIP on the destination backend and restart it. You do not add another public
allocation to OniLink for a normal backend route.

## Dashboard access

The initial owner setup code is written to:

```text
/home/container/dashboard/FIRST_RUN_SETUP.txt
```

Use the dashboard allocation through a TLS reverse proxy or a network access layer. Do not publish
plain HTTP to the open Internet. Enable TOTP for privileged accounts and protect `dashboard/` in
backups because it contains password hashes, sessions, audit data, and tenant definitions.

## Tenant hosting in the same panel

Tenant hosting does not create new Pterodactyl panels, eggs, or servers. The owner creates tenant
accounts and scoped proxy listeners in **Dashboard → Tenant Hosting**. Tenants sign in at the same
dashboard URL and see only **My Proxies** and their assigned backends.

Assign one additional UDP allocation to the existing OniLink Pterodactyl server for each tenant
listener. Enter that allocation in the tenant proxy form. A tenant backend still uses its own private
BDS allocation and unique OniBridge secret.

## Allowlist

Add an authenticated XUID before enabling the list:

```text
allowlist add 2533274790000001 ExamplePlayer
allowlist list
```

Then set `ALLOWLIST_ENABLED=true` or edit the matching configuration key and restart. An enabled
empty list blocks everyone. Use **Dashboard → Allowlist** for connected-player selection and normal
management.

## Startup order and validation

1. Start each backend and confirm OniBridge loads the exact production profile.
2. Start OniLink and confirm the stable update check completes.
3. Join the public OniLink UDP allocation.
4. Verify join/leave/rejoin data, commands, transfers, direct-join rejection, and allowlist behavior.
5. Check dashboard health and audit records.

Do not disable `shutdown_on_hook_failure`, direct-join rejection, profile approval, or firewall rules
to make an error disappear. Diagnose the first critical message with [Troubleshooting](TROUBLESHOOTING.md).
