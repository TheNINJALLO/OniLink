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
