# Linux

## Supported candidate target

| Item | Value |
| --- | --- |
| Operating system | Ubuntu 22.04 or newer |
| Architecture/ABI | x86-64, System V AMD64 |
| Minimum glibc | `2.35` |
| Locked BDS | `1.26.44.3` |
| BDS executable SHA-256 | `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7` |
| Endstone | `0.11.9` |
| Compiler/runtime | LLVM 18, C++20, libc++ 18 |
| Profile ID | `bds-1.26.44.3-linux-x86_64-06effdd00067f1ae` |

## Build evidence

The release `.so` is built natively on GitHub's Ubuntu 22.04 runner by `.github/workflows/linux-artifacts.yml`. That workflow builds both Java components, compiles and links the profile-specific native plugin, runs the native unit suite and synthetic executable hook harness, enforces a maximum `GLIBC_2.35` symbol requirement, packages the candidate, and uploads the exact release bundle.

The corrected `v0.1.0-candidate.1` library is ELF64 little-endian x86-64 and has SHA-256:

```text
418cc2a3c4119b5c7814535f4d25de9d88a8bf15d0196580d411d642850aa7b7
```

Verify it against the release's `SHA256SUMS`; the Linux compatibility manifest records `GLIBC_2.35` as both the highest required symbol version and the enforced maximum.

The build uses LLVM/libc++ 18 packages compiled for Ubuntu 22.04. The release gate requires `libc++.so.1` and rejects `libstdc++.so.6` or unresolved `std::__cxx11` symbols so a mixed C++ runtime cannot be published. Deploy it only in an Endstone environment providing glibc 2.35 or newer plus the matching C++ runtime libraries.

## Remaining validation

The GitHub-hosted build and synthetic hook harness pass, but production promotion still requires:

- Independent human review of the exact profile and generated adapter.
- Plugin load, hook install, shutdown cleanup, and unload evidence on the target host.
- Valid and hostile live join cases.
- Restart/rejoin inventory, Ender Chest, permission, ban, allowlist, command, and address continuity.
- Concurrency and backend-switch coverage.

The compatibility manifest therefore remains `production_ready=false`. See [Testing](TESTING.md) for the acceptance matrix.
