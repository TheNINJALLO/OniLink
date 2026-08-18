# Pterodactyl Setup

Run OniLink and every backend as separate Pterodactyl servers.

## Import the released egg

Download [`egg-onilink.json`](https://github.com/TheNINJALLO/OniLink/releases/download/v0.1.0-candidate.1/egg-onilink.json), then open **Admin Panel → Nests → Import Egg**.

Create an **OniLink Bedrock Proxy** server with the Java 21 image and one public UDP allocation. Set the private backend host/port and enter a generated Base64 secret in the administrator-only `ONIBRIDGE_FORWARDING_SECRET` variable. Put the same value on the backend validator.

The egg verifies its bootstrap downloads, creates `config.properties` only when missing, and preserves it on reinstall. Every container start checks the newest published OniLink release, including prereleases, and replaces the JAR only after checksum validation. A failed update starts the existing JAR; a successful one keeps `OniLink.jar.previous` for rollback. The BDS/Endstone and Geyser processes still use separate servers/eggs.

## Allocations

| Server | Port | Exposure |
| --- | --- | --- |
| OniLink | `19132/udp` | Public |
| BDS backend | `19133/udp` | Private/firewalled to OniLink |
| Geyser backend | `19134/udp` | Private/firewalled to OniLink |

## Administrator-only variables

Add each secret to OniLink and only its matching backend:

| Secret | OniLink | Backend |
| --- | --- | --- |
| `ONIBRIDGE_SURVIVAL_SECRET` | Yes | Survival BDS |
| `ONIBRIDGE_JAVA_SECRET` | Yes | Geyser |

Use different values. Do not put a secret into a public egg default, startup command, or configuration file.

The egg declares blank, non-user-viewable fields for the default, survival, and Java forwarding secrets. Panel administrators can access server variables, so protect administrator access and panel backups.

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
