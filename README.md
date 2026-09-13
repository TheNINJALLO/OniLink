<!-- onilink-professional-header:start -->
<p align="center">
  <img src="docs/assets/banner.svg" width="100%" alt="OniLink — standalone Bedrock edge system with secure backend identity forwarding">
</p>

<p align="center">
  <a href="https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml"><img alt="Linux build" src="https://img.shields.io/github/actions/workflow/status/TheNINJALLO/OniLink/linux-artifacts.yml?branch=main&amp;style=for-the-badge&amp;logo=githubactions&amp;logoColor=white&amp;label=Linux%20Build"></a>
  <a href="https://github.com/TheNINJALLO/OniLink/releases"><img alt="Latest stable release" src="https://img.shields.io/github/v/release/TheNINJALLO/OniLink?style=for-the-badge&amp;label=Release"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/TheNINJALLO/OniLink?style=for-the-badge"></a>
</p>

<p align="center">
  <img alt="Version 0.3.0" src="https://img.shields.io/badge/Release-v0.3.0-52b7a8?style=flat-square">
  <img alt="Endstone 0.11.10" src="https://img.shields.io/badge/Endstone-0.11.10-52b7a8?style=flat-square">
  <img alt="BDS 1.26.45.1" src="https://img.shields.io/badge/BDS-1.26.45.1-63b8ff?style=flat-square">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Linux x86-64" src="https://img.shields.io/badge/Linux-x86--64-8b7dff?style=flat-square&amp;logo=linux&amp;logoColor=white">
</p>

<p align="center">
  <strong>One authenticated Bedrock edge with secure identity forwarding and native player-data continuity across BDS servers.</strong>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> &bull;
  <a href="docs/README.md">Documentation</a> &bull;
  <a href="https://github.com/TheNINJALLO/OniLink/wiki">Wiki</a> &bull;
  <a href="https://github.com/TheNINJALLO/OniLink/releases">Releases</a> &bull;
  <a href="CONTRIBUTING.md">Contributing</a>
</p>
<!-- onilink-professional-header:end -->

> [!IMPORTANT]
> `v0.3.0` is the current stable release. Its exact Linux BDS `1.26.45.1` + Endstone `0.11.10`
> profile is production-approved from native CI and operator live acceptance. The separately built
> Windows profile remains candidate-only until its own live gate passes. Full packet captures can
> include chat, XUIDs, endpoints, decoded fields, and incoming bytes; restrict dashboard access and
> inspect exports before sharing them.

One running OniLink instance negotiates 1.26.44/2168, 1.26.45/2169, and 1.26.50/2192 independently
for each player. OniForward v3 uses signed, persisted one-time sequences, so separately hosted
OniLink and OniBridge systems do not depend on synchronized wall clocks.

## The product family

OniLink is an independent Bedrock edge system with its own runtime, control plane, configuration,
release stream, and public identity. Its supported product family has two runtime components:

| Component | Role | Runtime |
| --- | --- | --- |
| **OniLink** | Authenticates public Xbox clients, routes sessions, creates `OniForward` claims, and serves the secured dashboard | Java 21 |
| **OniBridge** | Validates `OniForward` locally and restores the verified XUID before BDS chooses player storage | Native C++20 Endstone plugin |

Repository tooling locks official BDS metadata, creates exact native profiles, validates compatibility,
and packages release files. BDS itself is never redistributed.

[v0.4.0-beta.2](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.4.0-beta.2) is available for
testing. It adds [1.26.50 protocol fixes and upstream monitoring](docs/CROSS_VERSION_1_26_50.md).
Its [Update Center](docs/UPDATE_CENTER.md) provides verified uploads, managed Endstone
maintenance, signed protocol packages, resource-pack releases and player/network services.
[Protocol packages and addon API 1](docs/PROTOCOL_PACKAGES.md) document independent updates and the SDK.
Follow the [beta upgrade instructions](docs/releases/v0.4.0-beta.2.md) on a test instance first.

For the next server update, the [BDS update workflow](docs/ADDING_BDS_VERSION.md) provides offline
file-set verification, repeatable imports, release-specific protocol diffs, and separate native gates.

## How it works

```text
Xbox-authenticated Bedrock client
                |
                v
       OniLink public listener
                |
                | one-time, backend-bound OniForward claim
                v
       OniBridge + Endstone + BDS
                |
                v
    verified XUID selected before player storage
```

The forwarding check makes no HTTP request. Claims are backend-bound, bridge-bound, single-use,
replay-protected, and accepted only from configured proxy CIDRs. Missing, replayed, retired,
incorrectly scoped, or incorrectly sourced claims fail closed.

## Quick start

1. Download [`v0.3.0`](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.3.0) and verify every file with `SHA256SUMS`.
2. Use the exact BDS `1.26.45.1` Linux executable and Endstone `0.11.10` for the production-approved native profile.
3. Generate a unique secret for the backend: `openssl rand -base64 32`.
4. Configure the same backend name, bridge ID, key ID, and secret source in OniLink and OniBridge.
5. Keep BDS private and restrict `trusted_proxy_cidrs` to the address BDS actually sees for OniLink.
6. Start the backend, start OniLink, and complete the [acceptance checklist](docs/TESTING.md).

```bash
gh release download v0.3.0 \
  --repo TheNINJALLO/OniLink \
  --dir onilink-release
```

The copyable [`single-bds`](examples/single-bds/) deployment, [quick start](docs/QUICKSTART.md), and
[complete installation guide](docs/INSTALLATION.md) include matching proxy and native configuration
examples. After the first route works, use **Dashboard → Add Backend** to generate each additional
route, unique secret, restricted key file, complete `onibridge.toml`, and setup ZIP.

## Operations dashboard

`OniLink.jar` includes a responsive control plane for players, XUID allowlisting, backend health,
transfers, alerts, bounded traces, packet compatibility monitoring, guided backend setup, tenant
accounts and scoped proxy listeners, configuration editing, logs, audit records, accounts, TOTP,
metrics, and redacted support bundles. It defaults to `127.0.0.1:8080`; first-run owner setup uses
the one-time code in `dashboard/FIRST_RUN_SETUP.txt`.

For remote use, place the dashboard behind HTTPS and restrict it to administrator networks. See the
[dashboard guide](docs/DASHBOARD.md). Hosting providers can create tenant logins and isolated proxy
listeners inside the same container; see [tenant hosting](docs/TENANT_HOSTING.md).

## Optional OniControl system

OniControl adds three disabled-by-default execution paths without changing OniForward: OniPacket
sends codec-validated presentation packets and evaluates bounded relay rules; OniVirtual owns
per-player menus, private entities, and fake blocks; and the signed `ONICTL/1` channel asks
OniBridge to perform real state changes on the BDS primary thread. It uses a separate secret for
every backend and refuses unsupported capabilities instead of treating a client packet as proof of
an authoritative change.

The embedded dashboard provides target resolution, preview and single-use confirmation, typed
plans, action history, rule management, and owner-managed tenant grants. Start with the
[OniControl guide](docs/ONICONTROL.md), review the [capability matrix](docs/COMPATIBILITY.md), and
keep all new gates disabled until the private TCP control route and exact backend profile are
verified.

## Compatibility

| Target | Status |
| --- | --- |
| Linux x86-64, BDS `1.26.45.1`, Endstone `0.11.10` | Production-approved exact profile |
| Windows x86-64, BDS `1.26.45.1`, Endstone `0.11.10` | Candidate; Windows live acceptance remains |
| Linux x86-64, BDS `1.26.44.3`, Endstone `0.11.9` | Historical production-approved profile |
| Bedrock `1.26.50` client (protocol `2192`) -> BDS `1.26.45.1` | Stable per-session cross-version route |

Use `onibridge-0.3.0-bds-1.26.45.1-linux-x86_64.so` only with the exact approved target. Unknown
executables, layouts, hook bytes, profiles, and Endstone builds remain blocked. Exact hashes and
remaining gates are in [Compatibility](docs/COMPATIBILITY.md).

The stable release accepts protocol `2192` clients and translates them to protocol `2169` or
an older supported backend protocol. Leave backend detection on `auto`; OniLink recognizes BDS
`1.26.45.x` as protocol 2169 and `1.26.44.x` as the protocol-2168 hotfix dialect. See the
[1.26.50 cross-version guide](docs/CROSS_VERSION_1_26_50.md) before testing a Preview client.

## Documentation

| Start here | Operator guides | Engineering reference |
| --- | --- | --- |
| [Documentation hub](docs/README.md) | [Configuration](docs/CONFIGURATION.md) | [Architecture](docs/ARCHITECTURE.md) |
| [Quick start](docs/QUICKSTART.md) | [Installation](docs/INSTALLATION.md) | [Identity flow](docs/IDENTITY_FLOW.md) |
| [Deployment example](examples/single-bds/) | [Add a backend](docs/ADDING_BACKEND.md) | [OniForward protocol](docs/ONIFORWARD_PROTOCOL.md) |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | [Dashboard](docs/DASHBOARD.md) | [Compatibility](docs/COMPATIBILITY.md) |
| [Packet monitor](docs/PACKET_MONITOR.md) | [Pterodactyl](docs/PTERODACTYL.md) | [Building](docs/BUILDING.md) |
| [1.26.50 cross-version preview](docs/CROSS_VERSION_1_26_50.md) | [Migration](docs/MIGRATION.md) | [Protocol Lab](docs/PROTOCOL_LAB.md) |
| [OniControl](docs/ONICONTROL.md) | [OniControl on Pterodactyl](docs/ONICONTROL_PTERODACTYL.md) | [OniControl security](docs/ONICONTROL_SECURITY.md) |
| [0.3 operations modules](docs/EXPANSION_MODULES.md) | [Protocol Lab](docs/PROTOCOL_LAB.md) | [Compatibility evidence](docs/COMPATIBILITY.md) |
| [Migration](docs/MIGRATION.md) | [Tenant hosting](docs/TENANT_HOSTING.md) | [Source audit](docs/SOURCE_AUDIT.md) |

## Build and test

On Ubuntu 22.04 with Java 21, Python 3, CMake, Ninja, LLVM 18, libc++ 18, and libc++abi 18:

```bash
scripts/build-linux.sh
```

The build tests OniLink and OniBridge, rejects a native library requiring newer than glibc 2.35,
and writes the release bundle to `dist/linux`. The same path runs in
[Linux Release Artifacts](https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml).

## Security and distribution

- BDS archives and executables are never committed or released.
- Secrets, complete tokens, production addresses, and player identifiers stay out of issues and source control.
- Keep the dashboard loopback-only or behind restricted HTTPS; protect `dashboard/` and its backups.
- Report vulnerabilities using the [security policy](SECURITY.md).

Project-owned source is distributed under [LICENSE](LICENSE). Third-party notices and provenance are
recorded in [NOTICE](NOTICE) and the [source audit](docs/SOURCE_AUDIT.md).
