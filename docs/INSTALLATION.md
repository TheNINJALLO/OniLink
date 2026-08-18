# Installation

OniLink supports two backend paths. Use the native OniBridge plugin for BDS + Endstone, or the OniBridge-Geyser extension for a Geyser-backed Java server.

> [!IMPORTANT]
> The current native artifacts are acceptance-test candidates. Match the release manifest to the exact BDS executable SHA-256, operating system, architecture, and Endstone `0.11.9` before installation.

## Release contents

| File | Install/use |
| --- | --- |
| `OniLink.jar` | Run on the public proxy host with Java 21 |
| `onilink.properties.example` | Copy to the proxy runtime as `config.properties` |
| `onibridge-0.1.0-bds-1.26.44.3-linux-x86_64.so` | Put in the Linux Endstone backend's `plugins/` directory |
| `onibridge.toml.example` | Configuration reference; the native plugin generates its live copy |
| `onibridge-profile-1.26.44.3-linux-x86_64.json` | Exact candidate evidence for review and test records |
| `OniBridge-Geyser.jar` | Put in Geyser's `extensions/` directory for a Java backend |
| `onibridge-geyser.properties.example` | Geyser extension configuration reference |
| `linux-compatibility-manifest.json` | Status, hashes, and remaining release gates |
| `SHA256SUMS` | Download integrity verification |

## Before installation

1. Download the matching release from [GitHub Releases](https://github.com/TheNINJALLO/OniLink/releases).
2. Verify every listed asset using `SHA256SUMS`.
3. Back up BDS worlds, Endstone plugin data, permissions, operators, allowlists, and any identity-indexed databases.
4. Generate a unique standard-Base64 secret containing at least 32 random bytes for each backend.
5. Store secrets in environment variables or restricted files, never in Git.
6. Make the backend UDP listener private or firewall it to the OniLink host.

## Native BDS + Endstone

### 1. Confirm the exact runtime

For the current Linux candidate, verify:

- BDS version: `1.26.44.3`
- BDS executable SHA-256: `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7`
- Platform: Linux x86-64 / System V AMD64
- Endstone: `0.11.9`
- Profile: `bds-1.26.44.3-linux-x86_64-06effdd00067f1ae`

Do not load the candidate on a merely similar version or on Windows.

### 2. Install the plugin

1. Stop BDS.
2. Copy the matching `.so` into the Endstone `plugins/` directory.
3. Start BDS once. OniBridge creates `plugins/onibridge/onibridge.toml` and intentionally shuts down because the generated defaults are incomplete.
4. Configure the generated file with the exact profile, backend name, bridge ID, key ID, secret source, and trusted proxy CIDR.
5. For candidate testing only, set `allow_unreviewed_profile=true`. Keep `allow_unknown_bds=false` and `allow_unknown_endstone=false`.
6. Start BDS with the forwarding secret available in its environment.
7. Confirm the console reports that the exact native identity hook is active.

Any startup shutdown, hash mismatch, expected-byte mismatch, profile mismatch, or hook failure is a failed compatibility test. Do not disable fail-closed behavior to continue.

### 3. Configure and start OniLink

1. Put `OniLink.jar` in a dedicated runtime directory.
2. Copy `onilink.properties.example` to `config.properties`.
3. Set the public listener and backend address.
4. Make `backend.<name>.forwarding.bridgeId`, `activeKeyId`, and `activeSecretEnv` match OniBridge.
5. Start the backend first.
6. Start the proxy:

```bash
java -jar OniLink.jar config.properties
```

Players connect only to the OniLink listener.

## Geyser + Java

Install `OniBridge-Geyser.jar` instead of the native `.so`. The Geyser Bedrock listener must be private, the extension and OniLink must use the same backend-specific forwarding settings, and OniLink must set `backend.<name>.dropSubChunkRequests=true`.

Follow the complete [Geyser integration guide](GEYSER.md).

## Acceptance

Complete [Testing](TESTING.md) before changing any compatibility record to production-ready. At minimum, test valid proxy joins, direct-join rejection, tamper/expiry/replay rejection, concurrency, restart/rejoin storage continuity, permissions, commands, bans, allowlists, and real client addressing.

Do not install a patched Endstone/Onistone runtime or a Python authentication plugin. BDS archives and executables are never distributed by this repository.
