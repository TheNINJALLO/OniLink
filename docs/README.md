# OniLink documentation

<p align="center">
  <img src="assets/banner.svg" width="100%" alt="OniLink and OniBridge documentation">
</p>

Use this page as the canonical map for operators, testers, and contributors. The [GitHub Wiki](https://github.com/TheNINJALLO/OniLink/wiki) provides a shorter task-oriented copy of the operator guidance.

> [!IMPORTANT]
> `v0.1.7` is the current production release. Tenant and backend setup clearly separate the player-facing proxy from the destination game server, and the exact Linux BDS `1.26.44.3` + Endstone `0.11.9` profile remains production-approved; other platforms and profiles retain separate compatibility gates.

## Start here

| Goal | Guide |
| --- | --- |
| Understand the system in five minutes | [Project README](../README.md) |
| Install the current Linux release | [Quick start](QUICKSTART.md) |
| Install and configure the complete network | [Installation](INSTALLATION.md) |
| Add another BDS server automatically | [Adding a BDS backend](ADDING_BACKEND.md) |
| Review the current changes and upgrade steps | [v0.1.7 release notes](releases/v0.1.7.md) |
| Copy a complete working configuration | [Deployment examples](../examples/README.md) |
| Import the OniLink Pterodactyl egg | [Pterodactyl](PTERODACTYL.md) |
| Provision isolated paid customer instances | [Tenant hosting](TENANT_HOSTING.md) |
| Configure and secure the operations UI | [Dashboard](DASHBOARD.md) |
| Connect a Geyser-backed Java server | [Geyser integration](GEYSER.md) |
| Resolve a failed startup or rejected join | [Troubleshooting](TROUBLESHOOTING.md) |

## Operator guides

- [Configuration](CONFIGURATION.md) — shared IDs, keys, token lifetime, listener trust, and rotation.
- [Adding a BDS backend](ADDING_BACKEND.md) — dashboard wizard, generated secrets/TOML, Pterodactyl upload, routing, and manual fallback.
- [Linux](LINUX.md) — supported ABI, runtime requirements, and Linux evidence.
- [Windows](WINDOWS.md) — Windows candidate status and required validation.
- [Pterodactyl](PTERODACTYL.md) — process separation, allocations, environment secrets, and startup order.
- [Tenant hosting](TENANT_HOSTING.md) — one shared dashboard, scoped customer logins, isolated in-process proxies, handoffs, and lifecycle controls.
- [Dashboard](DASHBOARD.md) — first-run ownership, roles, TOTP, HTTPS, operations, recovery, and panel setup.
- [Migration](MIGRATION.md) — preserving identity and storage continuity when introducing OniLink.
- [Testing](TESTING.md) — completed evidence and the live acceptance checklist.
- [Command compatibility](COMMAND_COMPATIBILITY.md) — proxy/backend command ownership and fixture expectations.

## Security and architecture

- [Architecture](ARCHITECTURE.md) — public edge, backend verification, and compatibility plane.
- [Identity flow](IDENTITY_FLOW.md) — exact authentication and pre-storage identity timeline.
- [OniForward protocol](ONIFORWARD_PROTOCOL.md) — claim schema, HMAC validation, context binding, and replay rules.
- [Compatibility](COMPATIBILITY.md) — exact hashes, profiles, platforms, and release gates.
- [Security policy](../SECURITY.md) — reporting and production safety requirements.

## Build and release engineering

- [Building](BUILDING.md) — reproducible Java/native builds and the Linux release workflow.
- [BDS acquisition](BDS_ACQUISITION.md) — explicit EULA gate and archive controls.
- [BDS profile generation](BDS_PROFILE_GENERATION.md) — per-platform analysis and candidate production.
- [Adding a BDS version](ADDING_BDS_VERSION.md) — version-update procedure.
- [Hook analysis](HOOK_ANALYSIS.md) — call-site evidence and native validation boundaries.
- [Source audit](SOURCE_AUDIT.md) — pinned upstream sources and provenance.
- [Feature parity](FEATURE_PARITY.md) — audited behavior and implementation coverage.

## Canonical facts

| Item | Current value |
| --- | --- |
| Application release | `v0.1.7` |
| Locked BDS | `1.26.44.3` |
| Endstone | `0.11.9` |
| Public proxy runtime | Java 21 |
| Native plugin | C++20, exact-profile build |
| Linux release CI | Ubuntu 22.04, LLVM/libc++ 18, maximum `GLIBC_2.35` import |
| Linux native profile status | Production-approved for the exact locked target |

When a value changes, update this page, the root README, the compatibility table, the Wiki, and the release manifest together.
