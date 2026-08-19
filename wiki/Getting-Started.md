# Getting Started

OniLink is the public Bedrock listener. Every backend stays private and validates a fresh signed `OniForward` claim before accepting identity data.

## Choose your path

| Backend | Install | Use when |
| --- | --- | --- |
| BDS + Endstone | `OniLink.jar` + matching native `onibridge.so`/`.dll` | You need native BDS player-data continuity |
| Geyser + Java | `OniLink.jar` + `OniBridge-Geyser.jar` | Bedrock players enter a Java server through Geyser |

Do not install both validators on the same backend path.

## Prerequisites

- Java 21 for OniLink.
- A private UDP route between proxy and backend.
- One unique 32-byte-or-stronger Base64 secret per backend.
- Synchronized clocks.
- A backup of worlds, permissions, operators, allowlists, and plugin data.
- For native BDS: an exact supported BDS/Endstone/platform combination.

## Download

```bash
gh release download v0.1.1 \
  --repo TheNINJALLO/OniLink \
  --dir onilink-release
cd onilink-release
sha256sum -c SHA256SUMS
```

Stop if any checksum differs.

## Shared configuration rule

These values must match on OniLink and the selected validator:

1. Backend name
2. Bridge ID
3. Active key ID
4. Secret bytes and source

The validator's trusted proxy CIDR must match the source address actually observed at the backend.

## Continue

- For the full beginning-to-end procedure, use [[Installation Guide]].
- For BDS, continue to [[Native BDS Setup]].
- For Java, continue to [[Geyser Java Setup]].
- For all available settings, see [[Configuration]].
- For copyable files, choose the [single-BDS](https://github.com/TheNINJALLO/OniLink/tree/main/examples/single-bds) or [mixed BDS/Geyser](https://github.com/TheNINJALLO/OniLink/tree/main/examples/mixed-bds-geyser) example.
