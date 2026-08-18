# Building and Releasing

## Linux candidate build

Install Java 21, Python 3, CMake, Ninja, Clang, libc++, and libc++abi, then run:

```bash
scripts/build-linux.sh
```

The script builds/tests OniLink and OniBridge-Geyser, compiles/tests native OniBridge with the committed Linux adapter, and packages `dist/linux`.

The same process runs in [Linux Candidate Artifacts](https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml) on Ubuntu 24.04.

## Release contents

A candidate bundle includes only project-owned runtime/configuration files:

- `OniLink.jar`
- profile-specific `onibridge.so`
- `OniBridge-Geyser.jar`
- example configurations
- exact profile JSON
- compatibility manifest
- `SHA256SUMS`

BDS archives, executables, symbols, dumps, worlds, secrets, and caches must never be published.

## Promotion gates

Do not publish a stable/production release until the manifest records human profile review, hook-harness evidence, and live testing for the exact target. Keep candidate status visible in the tag, title, README, compatibility table, and manifest.

See [Building](https://github.com/TheNINJALLO/OniLink/blob/main/docs/BUILDING.md), [Adding a BDS version](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ADDING_BDS_VERSION.md), and [BDS profile generation](https://github.com/TheNINJALLO/OniLink/blob/main/docs/BDS_PROFILE_GENERATION.md).
