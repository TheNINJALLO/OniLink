# Installation Guide

Stable `v0.2.0` supports OniLink plus native OniBridge for BDS/Endstone. Use the exact Linux BDS
`1.26.44.3` + Endstone `0.11.9` target for the production-approved profile.

## Address plan

| Service | Example | Exposure |
| --- | --- | --- |
| OniLink | `10.10.0.10:19132/udp` | Public/player-facing |
| Dashboard | `127.0.0.1:8080/tcp` | Protected HTTPS route only |
| Survival BDS | `10.10.0.20:19133/udp` | Private; OniLink only |

## Installation sequence

1. Download `v0.2.0` and verify `sha256sum -c SHA256SUMS`.
2. Generate a unique secret with `openssl rand -base64 32`.
3. Install `OniLink.jar` and copy `onilink.properties.example` to `config.properties`.
4. Configure the listener and one backend route using [[Configuration]].
5. Put the matching `.so` and profile JSON in the Endstone `plugins/` directory.
6. Configure `plugins/onibridge/onibridge.toml` with matching backend, bridge, key, secret source,
   and the actual proxy source CIDR.
7. Keep all compatibility bypasses disabled and firewall BDS to OniLink.
8. Start BDS first, confirm OniBridge installs the exact hook, then start OniLink.
9. Join through OniLink and test leave/rejoin data, commands, direct-join rejection, and switching.

The environment-name setting is not the secret itself:

```properties
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET
```

```toml
[forwarding]
active_secret_env = "ONIBRIDGE_SURVIVAL_SECRET"
active_secret_file = ""
```

Both processes must receive the same real Base64 value in `ONIBRIDGE_SURVIVAL_SECRET`.

For additional routes, use **Dashboard → Add Backend**. It creates matched proxy properties and a
setup ZIP containing the native configuration and restricted key file, so shell permission changes
are not required.

The complete copyable procedure, including systemd, firewall, allowlist, upgrades, and production
checklists, is in the repository's
[installation guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/INSTALLATION.md).
