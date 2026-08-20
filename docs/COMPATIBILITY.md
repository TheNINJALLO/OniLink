# Compatibility

The exact locked stable pair was resolved at `2026-08-18T01:04:39.088463Z`. The Linux row is production-approved; the Windows row remains a candidate. Runtime selection verifies BDS path, executable SHA-256, size, architecture, exact call bytes, call destination, profile ID, and Endstone `0.11.9` before installing the hook.

| BDS | Executable SHA-256 | OS/ABI | Endstone | Artifact | Review | Harness | Live | Commands |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.26.44.3 | `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7` | Linux x86-64/System V, glibc 2.35+ | 0.11.9 (`a73f76d3725b471a6d83783166edc004804faa1b`) | `onibridge-0.2.0-beta.2-bds-1.26.44.3-linux-x86_64.so` | production-approved | passed on GitHub Ubuntu 22.04 with ABI ceiling check | complete matrix operator-approved | operator-approved |
| 1.26.44.3 | `2d6518ddd25211aa51155fc015cd0393b29b2af74551a378b16f9a724ed771bd` | Windows x86-64/Microsoft x64 | 0.11.9 (`a73f76d3725b471a6d83783166edc004804faa1b`) | `onibridge-0.1.0-bds-1.26.44.3-windows-x86_64.dll` | candidate; human review required | passed under MSVC | server/plugin/hook/unload lifecycle passed offline; no joins | source/unit only; live fixtures not run |

Profile IDs:

- `bds-1.26.44.3-linux-x86_64-06effdd00067f1ae`
- `bds-1.26.44.3-windows-x86_64-2d6518ddd25211aa`

The Linux production artifact fails closed on every runtime mismatch and requires `allow_unreviewed_profile=false`. The Windows candidate remains restricted to controlled acceptance testing and is not promoted by the Linux approval.
