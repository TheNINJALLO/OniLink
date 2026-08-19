# Building and Releasing

## Linux release build

Install Java 21, Python 3, CMake, Ninja, LLVM 18, libc++ 18, and libc++abi 18, then run:

```bash
scripts/build-linux.sh
```

The script builds/tests OniLink and OniBridge-Geyser, compiles/tests native OniBridge with the committed Linux adapter, rejects imports newer than `GLIBC_2.35`, and packages `dist/linux` with the ABI result in its manifest.

The same process runs in [Linux Release Artifacts](https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml) on Ubuntu 22.04.

## Release contents

A release bundle includes only project-owned runtime/configuration files:

- `OniLink.jar`
- profile-specific `onibridge.so`
- `OniBridge-Geyser.jar`
- example configurations
- exact profile JSON
- compatibility manifest
- `SHA256SUMS`

BDS archives, executables, symbols, dumps, worlds, secrets, and caches must never be published.

## Promotion gates

Application release stability and native-profile approval are separate decisions. A normal OniLink release may contain an exact native profile whose manifest remains `production_ready=false`, but the release notes, README, compatibility table, and manifest must make that limitation explicit. Never mark a native profile production-ready until its human review, hook-harness evidence, and complete live testing are recorded for the exact target.

See [Building](https://github.com/TheNINJALLO/OniLink/blob/main/docs/BUILDING.md), [Adding a BDS version](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ADDING_BDS_VERSION.md), and [BDS profile generation](https://github.com/TheNINJALLO/OniLink/blob/main/docs/BDS_PROFILE_GENERATION.md).
