# Configuration

OniLink uses Java properties and OniBridge uses strict TOML. Unknown native OniBridge keys are rejected.

Reference templates:

- `OniLink/onilink.example.properties`
- `OniBridge/onibridge.example.toml`

## Values that must match

Every backend is a separate trust domain. Align these values exactly:

| Meaning | OniLink | OniBridge |
| --- | --- | --- |
| Backend name | backend list entry | `backend_name` |
| Bridge ID | `backend.<name>.forwarding.bridgeId` | `bridge_id` |
| Active key ID | `backend.<name>.forwarding.activeKeyId` | `forwarding.active_key_id` |
| Secret source | `activeSecretEnv` or `activeSecretFile` | `active_secret_env` or `active_secret_file` |
| Trusted source | proxy egress address | `trusted_proxy_cidrs` |

`forwarding.proxyId` identifies the OniLink instance and is included in replay identity. Use a stable unique value per proxy.

## Secret requirements

- Use standard Base64 encoding for at least 32 random bytes.
- Give every backend a different secret.
- For an additional native BDS server, prefer the dashboard wizard; it creates matched restricted files without requiring new egg variables.
- Environment variables remain suitable when the panel administrator can configure the same protected variable on both containers.
- Configure exactly one environment or file source on each side.
- Never put a real secret in source control, logs, screenshots, or support issues.

Example secret generation:

```bash
openssl rand -base64 32
```

## Token lifetime

OniLink defaults to a 5-second token lifetime. Validators cap lifetime at 10 seconds and allow only the configured clock skew. Keep proxy and backend clocks synchronized. Increasing the lifetime expands the replay window and should not be used to hide clock or network problems.

## Key rotation

1. Generate a new secret and key ID.
2. Configure the old key as the validator's optional previous key.
3. Configure the new active key on both OniLink and the backend validator.
4. Restart or reload the processes as required.
5. Wait longer than maximum token lifetime plus allowed skew.
6. Remove the previous key.

Never configure more than one previous key or reuse a key ID with different bytes.

## Network trust

`trusted_proxy_cidrs` must describe the source address the backend actually observes, not the player's public address. Use the narrowest possible CIDR—normally one `/32` IPv4 address or one `/128` IPv6 address—and enforce the same boundary in the firewall.

Direct-join rejection is defense in depth, not a replacement for network isolation.

## Native compatibility controls

| Setting | Safe policy |
| --- | --- |
| `shutdown_on_hook_failure` | `true` |
| `reject_direct_joins` | `true` |
| `identity.uuid_mode` | `preserve_backend` |
| `identity.verify_post_login_xuid` | `true` |
| `compatibility.allow_unknown_bds` | `false` |
| `compatibility.allow_unknown_endstone` | `false` |
| `compatibility.allow_unreviewed_profile` | `false` in production; candidate tests only |

Experimental proxy UUID mode can split UUID-indexed permissions, scoreboards, and plugin data. It is not validated.

## Properties syntax

In Java properties files, `#` starts a comment only at the beginning of a line. Do not append comments after values; the comment text becomes part of the value.

For the full OniLink option reference, use `OniLink/onilink.example.properties`. It documents listeners, backends, failover, switching, commands, permissions, security limits, server-list data, resource packs, registries, and compression.

## Ready-to-copy configurations

| Layout | Files |
| --- | --- |
| One BDS backend | [`examples/single-bds/`](../examples/single-bds/README.md) |

The complete deployment sequence and operating-system examples are in [Installation](INSTALLATION.md).

To add a native BDS route automatically, use the dashboard's dedicated **Add Backend** page and follow [Adding another BDS backend](ADDING_BACKEND.md). The wizard saves the proxy properties and key automatically, then gives you one matched setup ZIP containing the key, complete Endstone configuration, and installation instructions.

## OniLink listener and routing keys

| Key | Example | Meaning |
| --- | --- | --- |
| `listener.host` | `0.0.0.0` | Local interface for the public Bedrock listener |
| `listener.port` | `19132` | Public UDP port players connect to |
| `publicAddress` | `play.example.com:19132` | Address advertised when a reconnect-style transfer is required |
| `backend.name` | `survival` | Primary backend used for new joins without a forced-host route |
| `backend.host` | `10.10.0.20` | Legacy/default backend address used while building the default entry |
| `backend.port` | `19133` | Legacy/default backend UDP port |
| `backends` | `survival,lobby` | Ordered comma-separated backend names |
| `hubBackend` | `survival` | `/hub` and default failover destination |
| `backend.protocol` | `auto` | Global backend protocol probe/pin policy |
| `backend.<name>.host` | `10.10.0.20` | Named backend address |
| `backend.<name>.port` | `19133` | Named backend UDP port |
| `backend.<name>.protocol` | `1.26.44` | Optional per-backend protocol override |

Use `backend.protocol=auto` unless you have a deliberate, tested reason to pin. A stale version pin is harder to diagnose than a failed probe.

For a tenant proxy, the provider owner or tenant can change `backend.name` through **My Proxies →
Choose the primary server**. The control plane also synchronizes the compatibility `backend.host`
and `backend.port` values and restarts only that proxy. `hubBackend` remains independent.

## Dashboard keys

| Key | Safe default | Meaning |
| --- | --- | --- |
| `dashboard.enabled` | `true` | Starts the embedded control plane with OniLink |
| `dashboard.host` | `127.0.0.1` | Local TCP interface; keep loopback unless deliberately publishing through a protected route |
| `dashboard.port` | `8080` | Dashboard HTTP TCP port; may use the same numeric port as Bedrock UDP |
| `dashboard.sessionMinutes` | `480` | Browser-session lifetime, allowed range 15–10,080 minutes |
| `dashboard.dataDirectory` | `dashboard` | Account, audit, and first-run setup data beside the proxy configuration |
| `dashboard.maxRequestBytes` | `262144` | Maximum dashboard request body, allowed range 16 KiB–1 MiB |
| `dashboard.logTailLines` | `400` | Maximum log lines returned, allowed range 50–5,000 |

The dashboard is plain HTTP. Use loopback plus an SSH tunnel or a restricted HTTPS reverse proxy for remote administration. The [dashboard guide](DASHBOARD.md) covers first-run setup, roles, TOTP, Pterodactyl, backups, and recovery.

## OniLink forwarding keys

For backend `survival`, the prefix is `backend.survival.forwarding.`.

| Suffix | Default/example | Rule |
| --- | --- | --- |
| `enabled` | `true` | Must stay enabled for an OniBridge-protected backend |
| `bridgeId` | `survival-main` | Must equal validator `bridge_id` |
| `activeKeyId` | `key-2026-01` | Must equal validator active key ID |
| `activeSecretEnv` | `ONIBRIDGE_SURVIVAL_SECRET` | Configure this or `activeSecretFile`, never both |
| `activeSecretFile` | `/etc/onilink/secrets/survival.key` | Relative paths resolve beside OniLink's config file |
| `previousKeyId` | `key-2025-12` | Optional rotation metadata |
| `previousSecretEnv` | `ONIBRIDGE_SURVIVAL_SECRET_OLD` | Optional old secret during rotation |
| `previousSecretFile` | empty | File alternative for the old secret |
| `tokenLifetimeMillis` | `5000` | Allowed range `1..10000`; keep short |

Global `forwarding.proxyId=edge-1` identifies the OniLink instance. Every enabled backend must use a unique active secret source; OniLink rejects a configuration that reuses one source across backends.

## Join, switching, and failover keys

| Key | Recommended start | Meaning |
| --- | --- | --- |
| `join.try` | `survival,java` | Ordered backends for a new connection |
| `join.attemptsPerBackend` | `2` | Attempts per candidate |
| `failover.enabled` | `true` for multiple backends | Move players when a backend disappears |
| `failover.fallbacks` | `survival,java` | Global ordered fallback list |
| `backend.<name>.fallback` | `java` | Per-backend override; empty disables failover from that backend |
| `failover.onBackendKick` | `auto` | Distinguish restart-style disconnects from player-specific kicks |
| `switch.retryWindowMillis` | `30000` | Total retry window for `/server`/`/hub` |
| `switch.retryDelayMillis` | `3000` | Delay between attempts |
| `switch.timeoutMillis` | `20000` | Wait for target `StartGame` |
| `switch.connectTimeoutMillis` | `5000` | Per-attempt network connect timeout |
| `protocolFault.action` | `disconnect` | Do not hide protocol defects as failover |
| `protocolFault.logFile` | `logs/protocol-errors.log` | Dedicated protocol-error log; empty disables only the file |

For a single backend, start with `failover.enabled=false`. For multiple backends, ensure a backend's only fallback is not itself.

## Permissions and commands

| Key | Example | Meaning |
| --- | --- | --- |
| `permissions.admins` | `2533274790000000` | Comma-separated XUIDs or gamertags; XUIDs are preferred |
| `permissions.adminCommands` | `alert,allowlist,glist,perm,send` | Commands restricted to administrators |
| `backend.<name>.adminOnly` | `true` | Hide/restrict one backend |
| `commands.passthrough` | empty | Proxy command names passed to all backends by default |
| `backend.<name>.passthroughCommands` | `hub,server` | Per-backend passthrough override |

`perm` and `allowlist` remain administrator-only regardless of this setting because they grant control over the proxy and player access. Leave `permissions.admins` empty until you know the trusted XUID.

## Authenticated XUID allowlist

OniLink's allowlist works even though BDS must use `online-mode=false` and `allow-list=false`. OniLink validates the Xbox login chain at the public listener, extracts its signed XUID, and rejects an unlisted account before allocating a proxy session or contacting a backend. Gamertags in the file are display labels only and never authorize a login.

| Key | Safe default | Meaning |
| --- | --- | --- |
| `allowlist.enabled` | `false` | Enforce XUID membership at OniLink ingress |
| `allowlist.file` | `allowlist.properties` | Persistent XUID-to-label file; relative paths resolve beside `config.properties` |
| `allowlist.kickMessage` | `You are not allow-listed on this server.` | Message shown to a rejected or removed player |
| `allowlist.disconnectOnRemoval` | `true` | Immediately disconnect an online player when their XUID is removed |

Add at least your own XUID before enabling enforcement:

```text
# OniLink console; no leading slash
allowlist add 2533274790000001 ExamplePlayer
allowlist list
allowlist status
```

An authenticated player who is already connected can be added by name with `allowlist add ExamplePlayer`. From an authorized in-game account use `/onilink allowlist ...`. You can also use **Dashboard → Allowlist** to select a connected player or enter an XUID manually.

Then set and restart:

```properties
allowlist.enabled=true
allowlist.file=allowlist.properties
allowlist.kickMessage=You are not allow-listed on this server.
allowlist.disconnectOnRemoval=true
```

The generated file is ordinary Java properties syntax:

```properties
2533274790000001=ExamplePlayer
2533274790000000=Second player
```

Use XUIDs from authenticated OniLink player data, not UUIDs or guessed gamertags. `permissions.admins` does not bypass the allowlist. If you accidentally enable an empty list, use the Pterodactyl console or dashboard to add your XUID, or temporarily set `allowlist.enabled=false` and restart.

## Public listener security keys

| Key | Default | Guidance |
| --- | --- | --- |
| `security.rateLimit.enabled` | `true` | Keep enabled |
| `security.rateLimit.packetLimit` | `500` | Per-address datagrams per 10 ms tick |
| `security.rateLimit.globalPacketLimit` | `100000` | Global datagrams per tick |
| `security.sendConnectionCookie` | `true` | Keep enabled for address reachability proof |
| `security.maxConnectionsPerAddress` | `5` | Allow normal household NAT use |
| `security.maxConnectionAttempts` | `8` | New sessions per address/window |
| `security.connectionAttemptWindowMillis` | `10000` | Attempt-window duration |
| `security.requireXuid` | `true` | Reject authenticated chains without an XUID |
| `security.commandCooldownMillis` | `1000` | Proxy command rate limit; administrators are exempt |

Raise a limit only after logs prove a legitimate client is hitting it. Do not disable the rate limiter as the first troubleshooting step.

## Resource and presentation keys

| Key | Example | Meaning |
| --- | --- | --- |
| `motd` | `OniLink Network` | First server-list line |
| `subMotd` | `Survival Network` | Second server-list line |
| `gameType` | `Survival` | Server-list display only |
| `maxPlayers` | `40` | Advertised limit; not an enforcement control |
| `resourcePacks.dir` | `resource-packs` | Operator-supplied pack directory beside configuration |
| `resourcePacks.cacheBackendPacks` | `true` | Learn/cache backend packs |
| `crossBackendPalette` | `true` | Preserve custom item/entity registry visibility across switching |
| `compression` | `zlib` | Client batch compression; `zlib` is universal |
| `compressionThreshold` | `0` | Uncompressed batch size before compression |

## Complete native OniBridge key reference

| Key | Default/required | Notes |
| --- | --- | --- |
| `bridge_id` | required | Must match OniLink `bridgeId` |
| `backend_name` | required | Must equal the OniLink backend name |
| `trusted_proxy_cidrs` | required array | At least one actual proxy source CIDR |
| `shutdown_on_hook_failure` | `true` | Keep enabled |
| `reject_direct_joins` | `true` | Required; `false` is rejected |
| `forwarding.protocol` | `2` | Only protocol 2 is supported |
| `forwarding.active_key_id` | required | Must match OniLink |
| `forwarding.active_secret_env` | one source | Environment-variable name |
| `forwarding.active_secret_file` | one source | Restricted-file alternative |
| `forwarding.previous_key_id` | empty | Optional single previous key |
| `forwarding.previous_secret_env` | empty | Previous-key environment source |
| `forwarding.previous_secret_file` | empty | Previous-key file source |
| `forwarding.maximum_token_size` | `4096` | Allowed `256..65536` |
| `forwarding.maximum_lifetime_ms` | `10000` | Allowed `1..10000` |
| `forwarding.allowed_clock_skew_ms` | `2000` | Allowed `0..10000` |
| `forwarding.replay_cache_max_entries` | `10000` | Allowed `1..1000000` |
| `identity.uuid_mode` | `preserve_backend` | `proxy_experimental` is not validated |
| `identity.verify_post_login_xuid` | `true` | Keep enabled |
| `identity.store_verified_identities` | `true` | Retain verified identity lookup data |
| `commands.register_native_commands` | `true` | Registers `/onibridge` |
| `commands.command_namespace` | `onibridge` | Any other value is rejected |
| `commands.interfere_with_backend_commands` | `false` | `true` is rejected |
| `compatibility.required_profile` | required | Exact embedded profile ID |
| `compatibility.allow_unreviewed_profile` | `false` | Candidate test opt-in only |
| `compatibility.allow_unknown_bds` | `false` | `true` is rejected |
| `compatibility.allow_unknown_endstone` | `false` | `true` is rejected |
| `legacy_verification.enabled` | `false` | `true` is rejected |

Unknown and duplicate TOML keys are rejected. Strings must be quoted, booleans must be `true`/`false`, and CIDRs must be an array of quoted strings.

## OniControl, OniPacket, and OniVirtual

Every new subsystem is disabled by default. The complete Java-side template is in
[`onilink.example.properties`](../OniLink/onilink.example.properties); the matching native block is
in [`onibridge.example.toml`](../OniBridge/onibridge.example.toml).

```properties
control.enabled=false
control.mode=advisor
control.connectHost=127.0.0.1
control.connectPort=19132
control.bridgeId=default-bridge
control.backendName=default
control.keyId=control-key-1
control.secretEnvironment=ONILINK_CONTROL_SECRET
control.secretFile=
control.allowInsecurePrivateNetwork=false
control.allowPublicAddress=false
control.tls.enabled=false

packetRules.enabled=false
packetRules.maxRules=500
packetRules.maxInjectedPacketsPerDecision=16

virtualization.enabled=false
protocolLab.enabled=false
```

Multi-backend settings use `backend.<name>.control.*`; global `control.*` keys are compatibility
aliases for the default backend. Configure exactly one control secret source and do not reuse an
OniForward source. A cleartext connection is accepted automatically only on loopback. A literal
private address additionally requires `allowInsecurePrivateNetwork=true`; a public address also
requires the separate dangerous override. See [OniControl security](ONICONTROL_SECURITY.md) before
enabling either override.

The native `[control]` section contains the same bridge/backend/key identifiers, a different
environment variable or owner-only file, a private `listen_host`, and the exact OniLink source in
`trusted_proxy_cidrs`. Native TLS transport is intentionally unavailable in this version; cross-node
deployments needing confidentiality must use a private encrypted tunnel. The Java TLS client does
validate the server name and configured CA or SHA-256 certificate pin.
