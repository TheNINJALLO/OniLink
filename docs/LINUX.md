# Linux

## Supported release target

| Item | Value |
| --- | --- |
| Operating system | Ubuntu 22.04 or newer |
| Architecture/ABI | x86-64, System V AMD64 |
| Minimum glibc | `2.35` |
| Locked BDS | `1.26.45.1` |
| BDS executable SHA-256 | `8ba803f23d681816495c7ac83bdba4b9cd7165a3bee5aedd18fa0c8c3d408ec2` |
| Endstone | `0.11.10` |
| Compiler/runtime | LLVM 18, C++20, libc++ 18 |
| Profile ID | `bds-1.26.45.1-linux-x86_64-8ba803f23d681816` |

## Build evidence

The release `.so` is built natively on GitHub's Ubuntu 22.04 runner by `.github/workflows/linux-artifacts.yml`. That workflow builds both Java components, compiles and links the profile-specific native plugin, runs the native unit suite and synthetic executable hook harness, enforces a maximum `GLIBC_2.35` symbol requirement, and uploads the exact release bundle.

The `v0.3.0` library is ELF64 little-endian x86-64. Verify its SHA-256 against the release's
`SHA256SUMS`; the final value is also recorded in the [v0.3.0 release notes](releases/v0.3.0.md).

The Linux compatibility manifest records `GLIBC_2.35` as both the highest required symbol version and the enforced maximum.

The build uses LLVM/libc++ 18 packages compiled for Ubuntu 22.04. The published plugin is runtime-neutral: its only dynamic dependencies are `libc.so.6` and `libm.so.6`. The release gate accepts a runtime-neutral plugin or direct/host-resolved libc++ references while rejecting every `libstdc++.so` dependency and unresolved `std::__cxx11` or `GLIBCXX` symbol. Deploy it only in an Endstone environment providing glibc 2.35 or newer.

## Production approval

The exact Linux profile is production-approved. Its recorded gates include:

- Human review of the exact profile and generated adapter.
- Native plugin build, hook harness, load, and exact hook activation.
- Operator-approved live join, rejection, persistence, policy, command, concurrency, and switching acceptance.
- A passing glibc 2.35 ceiling and C++ runtime policy.

The compatibility manifest therefore reports `production_ready=true`, `profile_status=production`, and no release blockers. This approval applies only to the exact hash, platform, profile ID, and Endstone version in the table above.
