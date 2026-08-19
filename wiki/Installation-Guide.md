# Installation Guide

This page is the operator path from an empty host to a controlled OniLink candidate test. The repository's [complete installation manual](https://github.com/TheNINJALLO/OniLink/blob/main/docs/INSTALLATION.md) includes every command, setting table, systemd example, rotation procedure, and rollback step.

> [!IMPORTANT]
> `v0.1.0-candidate.2` is not production-approved. Native BDS testing requires the exact BDS `1.26.44.3` Linux executable, Endstone `0.11.9`, the candidate opt-in, and completion of the formal acceptance record.

## 1. Pick a topology

```text
Players
  |
  | public UDP 19132
  v
OniLink 10.10.0.10
  |
  +-- private UDP 19133 --> BDS + Endstone 10.10.0.20
  |
  +-- private UDP 19134 --> Geyser 10.10.0.30 --> Java
```

Use native OniBridge on BDS/Endstone. Use OniBridge-Geyser on Geyser/Java. Do not install both validators on one backend path.

## 2. Choose the exact matching values

| Value | BDS example | Geyser example |
| --- | --- | --- |
| Backend name | `survival` | `java` |
| Bridge ID | `survival-main` | `java-main` |
| Key ID | `key-2026-01` | `key-2026-01` |
| Secret variable | `ONIBRIDGE_SURVIVAL_SECRET` | `ONIBRIDGE_JAVA_SECRET` |
| Backend listener | `10.10.0.20:19133` | `10.10.0.30:19134` |
| Trusted proxy | `10.10.0.10/32` | `10.10.0.10/32` |

Backend name, bridge ID, key ID, and secret bytes must match on OniLink and the selected validator. Each backend needs different secret bytes.

## 3. Download and verify

```bash
gh release download v0.1.0-candidate.2 \
  --repo TheNINJALLO/OniLink \
  --dir onilink-candidate
cd onilink-candidate
sha256sum -c SHA256SUMS
```

Every file must report `OK`.

For native Linux BDS, also run:

```bash
sha256sum bedrock_server
```

Required hash:

```text
06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7
```

Stop if it differs.

## 4. Create one secret per backend

```bash
openssl rand -base64 32
```

Store the value in your panel, service environment, or secret manager. The survival value must exist on OniLink and Survival BDS. The Java value must exist on OniLink and Geyser. Never commit it.

## 5. Install the files

OniLink:

```text
/opt/onilink/
├── OniLink.jar
├── config.properties
├── cache/
└── logs/
```

Native BDS:

```text
<BDS root>/
└── plugins/
    ├── onibridge-0.1.0-bds-1.26.44.3-linux-x86_64.so
    └── onibridge/
        └── onibridge.toml
```

Geyser:

```text
<Geyser root>/
└── extensions/
    ├── OniBridge-Geyser.jar
    └── onibridge-geyser/
        └── config.properties
```

Start each validator once to create its data directory, then stop and configure it.

## 6. Copy a configuration set

- [Single BDS files](https://github.com/TheNINJALLO/OniLink/tree/main/examples/single-bds)
- [Mixed BDS + Geyser files](https://github.com/TheNINJALLO/OniLink/tree/main/examples/mixed-bds-geyser)

Then read [[Native BDS Setup]] or [[Geyser Java Setup]] for the exact validator settings.

Once the first server works, use **Configuration → Add BDS Backend** in the dashboard. It updates OniLink, creates a unique protected secret, and produces the two files to upload to the new Endstone server. Follow [[Adding Backends]] for the exact panel paths, field examples, routing options, and manual fallback.

## 7. Lock down networking

Only OniLink's player listener is public. Example backend rule:

```bash
sudo ufw allow from 10.10.0.10 to any port 19133 proto udp
sudo ufw deny 19133/udp
```

Use the actual proxy source observed by the backend. NAT/container networks can change it.

## 8. Start in order

1. Start BDS/Endstone or Geyser with its secret variable.
2. Confirm validator configuration succeeds.
3. Confirm native BDS reports the exact hook active.
4. Start OniLink with the matching secret variables.
5. Connect a test client to OniLink.
6. Confirm direct backend access is blocked/rejected.

## 9. Validate before promotion

Test valid, direct, tampered, expired, replayed, and untrusted joins; concurrency; restart/rejoin storage; permissions; bans; allowlist; commands; real address; and backend switching.

Continue with [[Compatibility and Testing]] and [[Troubleshooting]].
