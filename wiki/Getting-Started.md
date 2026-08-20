# Getting Started

OniLink is the public Bedrock listener. Every BDS/Endstone backend stays private and uses OniBridge
to validate a fresh signed `OniForward` claim before accepting identity data.

## Prerequisites

- Java 21 for OniLink.
- Exact BDS `1.26.44.3` and Endstone `0.11.9` for the approved Linux profile.
- A private UDP route between proxy and backend.
- One unique 32-byte-or-stronger Base64 secret per backend.
- Synchronized clocks and current backups.

## Download

```bash
gh release download v0.2.0 \
  --repo TheNINJALLO/OniLink \
  --dir onilink-release
cd onilink-release
sha256sum -c SHA256SUMS
```

Stop if any checksum differs.

## The matching rule

These values must match on OniLink and OniBridge:

1. backend name;
2. bridge ID;
3. active key ID;
4. secret bytes and source.

OniBridge's trusted proxy CIDR must match the source address the backend actually observes.

Continue with [[Installation Guide]], then [[Native BDS Setup]]. The copyable
[single-BDS example](https://github.com/TheNINJALLO/OniLink/tree/main/examples/single-bds) supplies
both sides of the initial configuration.
