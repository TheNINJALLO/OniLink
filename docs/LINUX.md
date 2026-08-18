# Linux

## Supported candidate target

| Item | Value |
| --- | --- |
| Operating system | Ubuntu 22.04 or newer |
| Architecture/ABI | x86-64, System V AMD64 |
| Locked BDS | `1.26.44.3` |
| BDS executable SHA-256 | `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7` |
| Endstone | `0.11.9` |
| Compiler/runtime | Clang, C++20, libc++ |
| Profile ID | `bds-1.26.44.3-linux-x86_64-06effdd00067f1ae` |

## Build evidence

The release `.so` is built natively on GitHub's Ubuntu 24.04 runner by `.github/workflows/linux-artifacts.yml`. That workflow builds both Java components, compiles and links the profile-specific native plugin, runs the native unit suite and synthetic executable hook harness, packages the candidate, and uploads the exact release bundle.

The `v0.1.0-candidate.1` library is ELF64 little-endian x86-64 and has SHA-256:

```text
77d81817fe2ba82163192306527eab49932b57f686fc1a36190d76eccc3938dd
```

The build uses Clang plus libc++/libc++abi development headers. Deploy it only in an Endstone environment providing the matching runtime libraries.

## Remaining validation

The GitHub-hosted build and synthetic hook harness pass, but production promotion still requires:

- Independent human review of the exact profile and generated adapter.
- Plugin load, hook install, shutdown cleanup, and unload evidence on the target host.
- Valid and hostile live join cases.
- Restart/rejoin inventory, Ender Chest, permission, ban, allowlist, command, and address continuity.
- Concurrency and backend-switch coverage.

The compatibility manifest therefore remains `production_ready=false`. See [Testing](TESTING.md) for the acceptance matrix.
