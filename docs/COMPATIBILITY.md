# Compatibility

The current exact stable pair, BDS `1.26.45.1`, was resolved at
`2026-09-01T03:02:09.667783Z`. Its Linux and Windows profiles are candidates. The historical Linux
BDS `1.26.44.3` profile remains production-approved; its Windows counterpart remains a candidate.
Runtime selection verifies BDS path, executable SHA-256, size, architecture, exact call bytes,
call destination, profile ID, and the profile's exact Endstone version before installing the hook.

| BDS | Executable SHA-256 | OS/ABI | Endstone | Artifact | Review | Harness | Live | Commands |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.26.44.3 | `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7` | Linux x86-64/System V, glibc 2.35+ | 0.11.9 (`a73f76d3725b471a6d83783166edc004804faa1b`) | `onibridge-0.3.0-beta.4-bds-1.26.44.3-linux-x86_64.so` | production-approved | passed on GitHub Ubuntu 22.04 with ABI ceiling check | complete matrix operator-approved | operator-approved |
| 1.26.44.3 | `2d6518ddd25211aa51155fc015cd0393b29b2af74551a378b16f9a724ed771bd` | Windows x86-64/Microsoft x64 | 0.11.9 (`a73f76d3725b471a6d83783166edc004804faa1b`) | `onibridge-0.3.0-beta.4-bds-1.26.44.3-windows-x86_64.dll` | candidate; human review required | passed under MSVC | server/plugin/hook/unload lifecycle passed offline; no joins | source/unit only; live fixtures not run |
| 1.26.45.1 | `8ba803f23d681816495c7ac83bdba4b9cd7165a3bee5aedd18fa0c8c3d408ec2` | Linux x86-64/System V, glibc 2.35+ | 0.11.10 (`8f84d6f5b556916597ed5b6b71329b2ed3ca8fc8`) | `onibridge-0.3.0-beta.10-bds-1.26.45.1-linux-x86_64.so` | candidate; static validation passed, human review required | beta.9 exact Linux release build passed CTest and GLIBC/libc++ gates; beta.10 rebuild pending | not run | source/unit only; live fixtures not run |
| 1.26.45.1 | `92d09c7b74ac6a9805bafc166d8e0a13ac9e5db73dbbb0819e5a14093699d44f` | Windows x86-64/Microsoft x64 | 0.11.10 (`8f84d6f5b556916597ed5b6b71329b2ed3ca8fc8`) | `onibridge-0.3.0-beta.10-bds-1.26.45.1-windows-x86_64.dll` | candidate; static validation passed, human review required | beta.9 exact plugin passed MSVC build and CTest; beta.10 rebuild pending | not run | source/unit only; live fixtures not run |

Profile IDs:

- `bds-1.26.44.3-linux-x86_64-06effdd00067f1ae`
- `bds-1.26.44.3-windows-x86_64-2d6518ddd25211aa`
- `bds-1.26.45.1-linux-x86_64-8ba803f23d681816`
- `bds-1.26.45.1-windows-x86_64-92d09c7b74ac6a98`

The Linux 1.26.44.3 production artifact fails closed on every runtime mismatch and requires
`allow_unreviewed_profile=false`. Both 1.26.45.1 profiles require the explicit candidate-test
override and are not promoted by the older Linux approval.

For application releases, CI validates the checked lock against both profile copies, generated ABI
facts, the adapter's embedded profile ID/executable hash/size/review flag, and any recorded runtime
evidence. Profile generation resolves official metadata, sends the Minecraft download-page headers
required by the official host, and compares both archives with independently reviewed SHA-256
values before inspection. Stable release jobs reacquire and verify the official archives and exact
executables. OniBridge repeats the executable hash, size, architecture, profile,
and call-site checks at plugin startup, so release metadata cannot authorize a different server
binary.

## OniControl capability evidence

The authenticated `/api/control/capabilities` response is the live source of truth. It intersects
the Java catalog with the target's negotiated codec, backend, signed OniBridge capability document,
feature gates, and actor scope. A class existing in the source tree is never treated as support.

| Capability | Evidence in this release | Status |
| --- | --- | --- |
| `PING`, `GET_CAPABILITIES` | Signed ONICTL/1 request/response fixtures, scope/replay/deadline tests | `SUPPORTED_WITH_LIMITS` pending a live control-channel run |
| `GET_BACKEND_HEALTH` | Native lifecycle and exact-profile state are returned without a BDS mutation | `SUPPORTED_WITH_LIMITS` pending a live control-channel run |
| `GET_PLAYER_STATE` | Authenticated XUID lookup through the pinned public Endstone API | `CANDIDATE` pending live player acceptance |
| `PREPARE_DRAIN` | Proxy-controlled drain readiness response; it does not claim BDS process control | `SUPPORTED_WITH_LIMITS` |
| `GET_ONLINE_PLAYERS`, `CLOSE_PLAYER_CONTAINERS`, `SAVE_WORLD` | Required public calls are absent from the pinned Endstone SDK | `UNSUPPORTED` |
| Any unrestricted command or arbitrary state mutation | Not advertised and rejected before native dispatch | `UNSUPPORTED` |
| Semantic client actions | Active-codec dry encoding and typed payload validation | `CANDIDATE`; action capability response provides the exact per-player result |
| OniPacket rules | Unit fixtures plus wiring in both real relay directions | `CANDIDATE` pending live relay acceptance |
| OniVirtual | Per-player bounded state and codec checks | `CANDIDATE` pending physical-client acceptance |
| Protocol Lab | Owner-only timed sessions, XUID/backend/model allowlists, rate limit, dry encoding, audit, and monitor origin | `CANDIDATE`; disabled by default and clientbound reviewed models only |
| Native TLS listener | No native TLS implementation in this release | `UNSUPPORTED`; use loopback or a private encrypted tunnel |

OniForge generates `compatibility-matrix.json` and `compatibility-matrix.md` from compiled codecs,
registered translator paths, tests, native profile metadata, and recorded live evidence. CI uploads
both as the `oniforge-compatibility` artifact. Packet ID equality is never semantic evidence.
