# Configuration

OniLink uses Java properties, native OniBridge uses strict TOML, and OniBridge-Geyser uses Java properties. Unknown native OniBridge keys are rejected.

Reference templates:

- `OniLink/onilink.example.properties`
- `OniBridge/onibridge.example.toml`
- `OniBridge-Geyser/config.example.properties`

## Values that must match

Every backend is a separate trust domain. Align these values exactly:

| Meaning | OniLink | Native OniBridge | OniBridge-Geyser |
| --- | --- | --- | --- |
| Backend name | backend list entry | `backend_name` | `backend_name` |
| Bridge ID | `backend.<name>.forwarding.bridgeId` | `bridge_id` | `bridge_id` |
| Active key ID | `backend.<name>.forwarding.activeKeyId` | `forwarding.active_key_id` | `active_key_id` |
| Secret environment | `backend.<name>.forwarding.activeSecretEnv` | `forwarding.active_secret_env` | `active_secret_env` |
| Trusted source | proxy egress address | `trusted_proxy_cidrs` | `trusted_proxy_cidrs` |

`forwarding.proxyId` identifies the OniLink instance and is included in replay identity. Use a stable unique value per proxy.

## Secret requirements

- Use standard Base64 encoding for at least 32 random bytes.
- Give every backend a different secret.
- Prefer environment variables for panels and containers.
- Restricted secret files are suitable for conventional hosts.
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
| BDS plus Geyser/Java | [`examples/mixed-bds-geyser/`](../examples/mixed-bds-geyser/README.md) |

The complete deployment sequence and operating-system examples are in [Installation](INSTALLATION.md).

## OniLink listener and routing keys

| Key | Example | Meaning |
| --- | --- | --- |
| `listener.host` | `0.0.0.0` | Local interface for the public Bedrock listener |
| `listener.port` | `19132` | Public UDP port players connect to |
| `publicAddress` | `play.example.com:19132` | Address advertised when a reconnect-style transfer is required |
| `backend.name` | `survival` | Default backend name |
| `backend.host` | `10.10.0.20` | Legacy/default backend address used while building the default entry |
| `backend.port` | `19133` | Legacy/default backend UDP port |
| `backends` | `survival,java` | Ordered comma-separated backend names |
| `hubBackend` | `survival` | `/hub` and default failover destination |
| `backend.protocol` | `auto` | Global backend protocol probe/pin policy |
| `backend.<name>.host` | `10.10.0.20` | Named backend address |
| `backend.<name>.port` | `19133` | Named backend UDP port |
| `backend.<name>.protocol` | `1.26.44` | Optional per-backend protocol override |
| `backend.<name>.dropSubChunkRequests` | `true` | Required for Geyser-like backends that do not implement BDS sub-chunk requests |

Use `backend.protocol=auto` unless you have a deliberate, tested reason to pin. A stale version pin is harder to diagnose than a failed probe.

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
| `permissions.adminCommands` | `alert,glist,perm,send` | Commands restricted to administrators |
| `backend.<name>.adminOnly` | `true` | Hide/restrict one backend |
| `commands.passthrough` | empty | Proxy command names passed to all backends by default |
| `backend.<name>.passthroughCommands` | `hub,server` | Per-backend passthrough override |

`perm` remains administrator-only because it grants permissions. Leave `permissions.admins` empty until you know the trusted XUID.

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
| `subMotd` | `Survival and Java` | Second server-list line |
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

## Complete OniBridge-Geyser key reference

| Key | Default/required | Notes |
| --- | --- | --- |
| `bridge_id` | required | Must match OniLink |
| `backend_name` | required | Must match OniLink backend name |
| `trusted_proxy_cidrs` | required CSV | Exact proxy sources |
| `active_key_id` | required | Must match OniLink |
| `active_secret_env` | one source | Recommended source |
| `active_secret_file` | one source | Requires verifiable POSIX permissions |
| `previous_key_id` | empty | Optional single previous key |
| `previous_secret_env` | empty | Previous environment source |
| `previous_secret_file` | empty | Previous file source |
| `maximum_token_size` | `4096` | Allowed `256..65536` |
| `maximum_lifetime_millis` | `10000` | Allowed `1..10000` |
| `allowed_clock_skew_millis` | `2000` | Allowed `0..10000` |
| `replay_cache_maximum_entries` | `10000` | Allowed `1..1000000` |

Unknown keys are rejected. A missing/invalid extension state rejects every join.
