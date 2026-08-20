# Pterodactyl Setup

Import `egg-onilink.json` from the stable
[`v0.2.0` release](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.2.0). Create one OniLink
server and keep every BDS/Endstone backend in its own existing Pterodactyl server.

## Allocations

| Allocation | Purpose |
| --- | --- |
| One public UDP port | Player-facing OniLink listener |
| One protected TCP port | Dashboard |
| One extra UDP port per tenant | Tenant listener inside the same OniLink container |

Normal backend routes do not need extra OniLink allocations. The destination IP and UDP port belong
to the separate BDS server.

## Updates

Every reboot checks the configured channel and verifies the JAR, updater, reference configuration,
and checksums before installing changes atomically.

| Channel | Behavior |
| --- | --- |
| `stable` | Latest non-prerelease; default |
| `beta` | Latest release including prereleases |
| `pinned` | Exact `ONILINK_VERSION` |

The updater preserves `config.properties` and starts the existing JAR if an update fails. Reimport
the current egg and run **Reinstall Server** once to bootstrap updates on an older installation.

Store the real forwarding secret in protected panel variables on both OniLink and BDS. The config
field contains only the variable name. If the backend egg cannot expose a protected variable, use
the **Add Backend** setup ZIP and its restricted key file.

The initial dashboard owner code is in `/home/container/dashboard/FIRST_RUN_SETUP.txt`. Publish the
dashboard only through protected HTTPS. For exact variables, example addresses, complete TOML, and
tenant instructions, use the canonical
[Pterodactyl guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/PTERODACTYL.md).
