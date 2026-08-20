<p align="center">
  <img src="../docs/assets/banner.svg" width="100%" alt="OniLink and OniBridge">
</p>

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Bedrock proxy" src="https://img.shields.io/badge/Role-Bedrock%20Edge-52b7a8?style=flat-square">
  <img alt="Fail closed" src="https://img.shields.io/badge/Security-Fail%20Closed-8b7dff?style=flat-square">
</p>

# OniLink proxy

OniLink is the public Java 21 Bedrock edge for authentication, backend discovery, routing, failover, switching, adjacent protocol translation, packet relay, resource-pack caching, registry continuity, permissions, addons, and connection controls.

## Key behavior

- Validates the public Mojang/Xbox client identity chain.
- Creates a fresh backend-bound and bridge-bound `OniForward` claim for every connection attempt.
- Supports native BDS/Endstone backends through OniBridge.
- Keeps `/onilink` as its collision-resistant proxy namespace while leaving unrelated backend commands authoritative.
- Preserves failover, resource packs, custom registries, and per-backend protocol behavior.

## Configure

Copy `onilink.example.properties` to `config.properties`. Each protected backend needs a unique forwarding secret supplied through an environment variable or restricted file. Backend name, bridge ID, key ID, and secret must match its validator.

See the [configuration guide](../docs/CONFIGURATION.md), [quick start](../docs/QUICKSTART.md), and [native installation guide](../docs/INSTALLATION.md).

## Run

```bash
java -jar OniLink.jar config.properties
```

Start protected backends first and expose only the OniLink listener to players.

## Build

```bash
./gradlew test standaloneJar
```

The standalone artifact is written to `dist/OniLink.jar`. The repository-level Linux workflow builds and tests OniLink together with OniBridge.
