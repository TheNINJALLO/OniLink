# OniControl implementation baseline

The OniControl work began from clean commit
`892668199306e2de1337c5db8195a296eb1ea8f1` on 2026-08-19. That commit contains only the
post-release provenance update on top of the v0.2.0 source commit
`15fd41af74fc2f2121ae013712c54dedec6e641f`; the Java, native, dashboard, packaging, and test
sources are identical.

No source changes were present when this baseline was captured.

| Gate | Result |
| --- | --- |
| Java/Gradle | 418 passed, 0 failed, 0 skipped |
| Dashboard formatting | passed |
| Dashboard lint | passed with zero warnings |
| Dashboard TypeScript check | passed |
| Dashboard tests | 30 passed in 4 files |
| Dashboard production build | passed, 1,962 modules transformed |
| Packaging, egg, and ABI Python suite | 25 passed, 3 skipped |
| `bdsctl` Python suite | 26 passed |
| `sdkgen` Python suite | 17 passed |

The Java and dashboard gates were run from a disposable copy beneath the local temporary
directory because OneDrive held locks on composite-build class directories in the workspace. The
first workspace run failed only while Gradle tried to delete those locked generated directories;
the unchanged disposable copy completed successfully.

This Windows workstation did not have CMake or a C++ compiler installed. Native baseline evidence
therefore comes from the already-published v0.2.0 source build:

- Cross-platform CI run `32325686708` passed the native core and test executable on Ubuntu 24.04
  and Windows Server 2022.
- Linux artifact run `32325686641` passed the exact profile build on Ubuntu 22.04, including the
  native tests and GLIBC 2.35 compatibility gate.

The OniControl implementation must preserve these gates and must not alter the existing
OniForward identity, profile-validation, replay-protection, XUID restoration, or player-storage
selection paths.
