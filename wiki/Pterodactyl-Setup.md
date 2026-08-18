# Pterodactyl Setup

Run OniLink and every backend as separate Pterodactyl servers.

## Allocations

| Server | Port | Exposure |
| --- | --- | --- |
| OniLink | `19132/udp` | Public |
| BDS backend | `19133/udp` | Private/firewalled to OniLink |
| Geyser backend | `19134/udp` | Private/firewalled to OniLink |

## Masked variables

Add each secret to OniLink and only its matching backend:

| Secret | OniLink | Backend |
| --- | --- | --- |
| `ONIBRIDGE_SURVIVAL_SECRET` | Yes | Survival BDS |
| `ONIBRIDGE_JAVA_SECRET` | Yes | Geyser |

Use different values. Do not put a secret into a public egg default, startup command, or configuration file.

## OniLink startup

```bash
java -jar OniLink.jar config.properties
```

Persist `config.properties`, `cache/`, `logs/`, and resource packs.

## Backend networking

Wings/Docker may present traffic from a bridge, node, NAT gateway, or proxy-container address. Set `trusted_proxy_cidrs` to what the backend actually observes. Prefer one `/32` or `/128`; do not trust an entire provider network.

## Startup order

1. Backend
2. Validator configuration/hook confirmation
3. OniLink
4. Test client

Use the full [Pterodactyl guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/PTERODACTYL.md) for layouts and complete example configuration.
