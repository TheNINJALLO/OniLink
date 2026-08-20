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
  <img alt="Version 0.2.0" src="https://img.shields.io/badge/Release-v0.2.0-52b7a8?style=flat-square">
  <img alt="Endstone 0.11.9" src="https://img.shields.io/badge/Endstone-0.11.9-52b7a8?style=flat-square">
  <img alt="BDS 1.26.44.3" src="https://img.shields.io/badge/BDS-1.26.44.3-63b8ff?style=flat-square">
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
> `v0.2.0` is the current stable release. The Linux BDS `1.26.44.3` + Endstone `0.11.9` profile is production-approved and remains fail closed. Full packet captures can include chat, XUIDs, endpoints, decoded fields, and incoming bytes; restrict dashboard access and inspect exports before sharing them.

## The product family

OniLink is an independent Bedrock edge system with its own runtime, control plane, configuration,
release stream, and public identity. Its supported product family has two runtime components:

| Component | Role | Runtime |
| --- | --- | --- |
| **OniLink** | Authenticates public Xbox clients, routes sessions, creates `OniForward` claims, and serves the secured dashboard | Java 21 |
| **OniBridge** | Validates `OniForward` locally and restores the verified XUID before BDS chooses player storage | Native C++20 Endstone plugin |

Repository tooling locks official BDS metadata, creates exact native profiles, validates compatibility,
and packages release files. BDS itself is never redistributed.

## How it works

```text
Xbox-authenticated Bedrock client
                |
                v
       OniLink public listener
                |
                | short-lived, backend-bound OniForward claim
                v
       OniBridge + Endstone + BDS
                |
                v
    verified XUID selected before player storage
```

The forwarding check makes no HTTP request. Claims are backend-bound, bridge-bound, short-lived,
replay-protected, and accepted only from configured proxy CIDRs. Missing, expired, replayed,
incorrectly scoped, or incorrectly sourced claims fail closed.

## Quick start

1. Download [`v0.2.0`](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.2.0) and verify every file with `SHA256SUMS`.
2. Use the exact BDS `1.26.44.3` Linux executable and Endstone `0.11.9` for the production-approved native profile.
3. Generate a unique secret for the backend: `openssl rand -base64 32`.
4. Configure the same backend name, bridge ID, key ID, and secret source in OniLink and OniBridge.
5. Keep BDS private and restrict `trusted_proxy_cidrs` to the address BDS actually sees for OniLink.
6. Start the backend, start OniLink, and complete the [acceptance checklist](docs/TESTING.md).

```bash
gh release download v0.2.0 \
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

## Compatibility

| Target | Status |
| --- | --- |
| Linux x86-64, BDS `1.26.44.3`, Endstone `0.11.9` | Production-approved exact profile |
| Windows x86-64, BDS `1.26.44.3`, Endstone `0.11.9` | Candidate; live client acceptance remains |

Use `onibridge-0.2.0-bds-1.26.44.3-linux-x86_64.so` only with the exact approved target. Unknown
executables, layouts, hook bytes, profiles, and Endstone builds remain blocked. Exact hashes and
remaining gates are in [Compatibility](docs/COMPATIBILITY.md).

## Documentation

| Start here | Operator guides | Engineering reference |
| --- | --- | --- |
| [Documentation hub](docs/README.md) | [Configuration](docs/CONFIGURATION.md) | [Architecture](docs/ARCHITECTURE.md) |
| [Quick start](docs/QUICKSTART.md) | [Installation](docs/INSTALLATION.md) | [Identity flow](docs/IDENTITY_FLOW.md) |
| [Deployment example](examples/single-bds/) | [Add a backend](docs/ADDING_BACKEND.md) | [OniForward protocol](docs/ONIFORWARD_PROTOCOL.md) |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | [Dashboard](docs/DASHBOARD.md) | [Compatibility](docs/COMPATIBILITY.md) |
| [Packet monitor](docs/PACKET_MONITOR.md) | [Pterodactyl](docs/PTERODACTYL.md) | [Building](docs/BUILDING.md) |
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
