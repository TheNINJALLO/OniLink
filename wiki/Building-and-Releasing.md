# Building and Releasing

## Linux release build

Install Java 21, Python 3, CMake, Ninja, LLVM 18, libc++ 18, and libc++abi 18, then run:

```bash
scripts/build-linux.sh
```

The script builds/tests OniLink, compiles/tests native OniBridge with the committed Linux adapter, rejects imports newer than `GLIBC_2.35`, and packages `dist/linux` with the ABI result in its manifest.

The same process runs in [Linux Release Artifacts](https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml) on Ubuntu 22.04.

## Release contents

A release bundle includes only project-owned runtime/configuration files:

- `OniLink.jar`
- profile-specific `onibridge.so`
- example configurations
- exact profile JSON
- compatibility manifest
- `SHA256SUMS`

BDS archives, executables, symbols, dumps, worlds, secrets, and caches must never be published.

## Promotion gates

Application release stability and native-profile approval are separate decisions. The current exact Linux profile completed its human review, hook-harness evidence, and operator-approved live testing, so its manifest reports `production_ready=true`. New BDS versions and other platforms must pass their own gates and must never inherit this approval.

See [Building](https://github.com/TheNINJALLO/OniLink/blob/main/docs/BUILDING.md), [Adding a BDS version](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ADDING_BDS_VERSION.md), and [BDS profile generation](https://github.com/TheNINJALLO/OniLink/blob/main/docs/BDS_PROFILE_GENERATION.md).
