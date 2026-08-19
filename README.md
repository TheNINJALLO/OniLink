<!-- onilink-professional-header:start -->
<p align="center">
  <img src="docs/assets/banner.svg" width="100%" alt="OniLink and OniBridge — fail-closed identity forwarding for Bedrock Dedicated Server and Geyser backends">
</p>

<p align="center">
  <a href="https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml"><img alt="Linux build" src="https://img.shields.io/github/actions/workflow/status/TheNINJALLO/OniLink/linux-artifacts.yml?branch=main&amp;style=for-the-badge&amp;logo=githubactions&amp;logoColor=white&amp;label=Linux%20Build"></a>
  <a href="https://github.com/TheNINJALLO/OniLink/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/TheNINJALLO/OniLink?include_prereleases&amp;style=for-the-badge&amp;label=Release"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/TheNINJALLO/OniLink?style=for-the-badge"></a>
</p>

<p align="center">
  <img alt="Endstone 0.11.9" src="https://img.shields.io/badge/Endstone-0.11.9-52b7a8?style=flat-square">
  <img alt="BDS 1.26.44.3" src="https://img.shields.io/badge/BDS-1.26.44.3-63b8ff?style=flat-square">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Linux x86-64" src="https://img.shields.io/badge/Linux-x86--64-8b7dff?style=flat-square&amp;logo=linux&amp;logoColor=white">
</p>

<p align="center">
  <strong>One authenticated Bedrock edge, secure identity forwarding, and native player-data continuity across BDS and Geyser-backed Java servers.</strong>
</p>

<p align="center">
  <a href="#quick-start">Quick start</a> &bull;
  <a href="#choose-a-backend-path">Backend paths</a> &bull;
  <a href="docs/README.md">Documentation</a> &bull;
  <a href="https://github.com/TheNINJALLO/OniLink/wiki">Wiki</a> &bull;
  <a href="https://github.com/TheNINJALLO/OniLink/releases">Releases</a>
</p>
<!-- onilink-professional-header:end -->

> [!IMPORTANT]
> `v0.1.0-candidate.1` is an acceptance-test release, not a production approval. The Linux build and synthetic hook harness pass on GitHub's Ubuntu runner, but the exact BDS profile still requires human review and a live join/storage test before promotion.

## What this repository provides

| Component | Role | Runtime |
| --- | --- | --- |
| **OniLink** | Authenticates the public Xbox client, routes sessions, creates `OniForward` claims, and serves the secured operations dashboard | Java 21 |
| **OniBridge** | Validates `OniForward` locally and restores the verified XUID before BDS chooses player storage | Native C++20 Endstone plugin |
| **OniBridge-Geyser** | Applies the same fail-closed claim validation before Geyser connects to a Java backend | Geyser 2.11 extension, Java 21 |
| **BDS tooling** | Locks official archives, generates exact per-platform candidates, validates profiles, and packages releasable files | Python 3.11+ |

The forwarding check makes no HTTP request. Tokens are backend-bound, bridge-bound, short-lived, replay-protected, and accepted only from configured proxy CIDRs.

## How it works

```text
Xbox-authenticated Bedrock client
                |
                v
       OniLink public listener
        |                   |
        | signed            | signed
        | OniForward        | OniForward
        v                   v
  OniBridge + BDS     OniBridge-Geyser
  pre-storage XUID       Java backend
      restoration
```

OniLink preserves the authenticated name, XUID, UUID, and observed client address. Each backend receives a fresh signed claim. A missing, expired, replayed, incorrectly scoped, or incorrectly sourced claim is rejected.

## Quick start

1. Read the [candidate release notice](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.1.0-candidate.1) and download only the files for your backend path.
2. Verify the download directory against `SHA256SUMS`.
3. Generate a unique 32-byte-or-stronger secret for each backend and expose it through an environment variable—not a committed configuration file.
4. Configure matching backend name, bridge ID, key ID, and secret environment variable on both sides.
5. Keep the backend listener private and restrict its trusted proxy CIDRs to OniLink.
6. Choose a ready-to-copy [deployment example](examples/README.md), follow the [complete installation guide](docs/INSTALLATION.md), then complete the [acceptance checklist](docs/TESTING.md).

Download the current candidate with GitHub CLI:

```bash
gh release download v0.1.0-candidate.1 \
  --repo TheNINJALLO/OniLink \
  --dir onilink-candidate
```

Pterodactyl administrators can import [`egg-onilink.json`](packaging/pterodactyl/egg-onilink.json) from the same release. The egg verifies its bootstrap files, preserves live configuration and dashboard data, checks for the newest published OniLink JAR on every container start, and serves the authenticated dashboard over TCP on the primary allocation alongside Bedrock UDP.

## Operations dashboard

`OniLink.jar` includes a responsive control plane for live players, backend health, transfers, alerts, bounded traces, safe configuration editing, logs, audit records, accounts, TOTP, metrics, and redacted support bundles. It defaults to `127.0.0.1:8080`; first-run owner setup uses a one-time code written to `dashboard/FIRST_RUN_SETUP.txt`.

For remote use, put the dashboard behind HTTPS and restrict it to administrator networks. See the [complete dashboard guide](docs/DASHBOARD.md) for standalone, reverse-proxy, account, role, recovery, and Pterodactyl examples.

## Choose a backend path

### Native BDS + Endstone

Use `onibridge-0.1.0-bds-1.26.44.3-linux-x86_64.so` with the exact BDS `1.26.44.3` Linux executable and Endstone `0.11.9`. Install the matching profile JSON, start once to generate `onibridge.toml`, configure it, and keep `allow_unreviewed_profile=true` limited to controlled candidate testing.

[Native installation guide →](docs/INSTALLATION.md)

### Geyser + Java backend

Put `OniBridge-Geyser.jar` in Geyser's `extensions/` directory. Bind Geyser's Bedrock listener to the private proxy-facing interface, align the `OniForward` settings, and enable `backend.<name>.dropSubChunkRequests=true` in OniLink.

[Geyser integration guide →](docs/GEYSER.md)

## Compatibility status

| Target | Build/test status | Production status |
| --- | --- | --- |
| Linux x86-64, BDS `1.26.44.3`, Endstone `0.11.9` | GitHub-hosted native build, unit tests, and synthetic hook harness pass | Candidate; human review and live BDS joins remain |
| Windows x86-64, BDS `1.26.44.3`, Endstone `0.11.9` | Native unit/harness and offline plugin lifecycle pass | Candidate; live client joins remain |
| Geyser `2.11` | Java build plus protocol/security tests pass | Candidate; live Geyser/Floodgate joins remain |

Exact executable hashes, profile IDs, and remaining gates are maintained in [Compatibility](docs/COMPATIBILITY.md).

## Documentation

| Start here | Operator guides | Engineering reference |
| --- | --- | --- |
| [Documentation hub](docs/README.md) | [Configuration](docs/CONFIGURATION.md) | [Architecture](docs/ARCHITECTURE.md) |
| [Quick start](docs/QUICKSTART.md) | [Linux](docs/LINUX.md) | [Identity flow](docs/IDENTITY_FLOW.md) |
| [Installation](docs/INSTALLATION.md) | [Geyser](docs/GEYSER.md) | [OniForward protocol](docs/ONIFORWARD_PROTOCOL.md) |
| [Deployment examples](examples/README.md) | [Windows status](docs/WINDOWS.md) | [Compatibility](docs/COMPATIBILITY.md) |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | [Dashboard](docs/DASHBOARD.md) | [Building](docs/BUILDING.md) |
| [GitHub Wiki](https://github.com/TheNINJALLO/OniLink/wiki) | [Pterodactyl](docs/PTERODACTYL.md) | [Source audit](docs/SOURCE_AUDIT.md) |
| [Migration](docs/MIGRATION.md) | [Testing](docs/TESTING.md) | [Feature parity](docs/FEATURE_PARITY.md) |

## Build and test

On Ubuntu 22.04 with Java 21, Python 3, CMake, Ninja, LLVM 18, libc++ 18, and libc++abi 18 installed:

```bash
scripts/build-linux.sh
```

This builds and tests OniLink, OniBridge, and OniBridge-Geyser, rejects a native library requiring newer than glibc 2.35, then writes the candidate bundle to `dist/linux`. The same path runs in [Linux Candidate Artifacts](https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml).

## Security and distribution

- BDS archives and executables are never committed or released.
- Real forwarding secrets, production addresses, player identifiers, and complete tokens must stay out of issues and source control.
- Keep the dashboard loopback-only or behind restricted HTTPS; protect `dashboard/` and its backups as credential material.
- Unknown executable hashes, hook bytes, layouts, Endstone builds, token contexts, or proxy sources fail closed.
- Report vulnerabilities using the [security policy](SECURITY.md).

## License

Project-owned source is distributed under the terms in [LICENSE](LICENSE). Third-party notices and provenance are recorded in [NOTICE](NOTICE) and the [source audit](docs/SOURCE_AUDIT.md).
