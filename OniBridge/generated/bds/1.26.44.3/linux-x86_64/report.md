# BDS authentication ABI report: linux-x86_64

- BDS: `1.26.44.3`
- Executable SHA-256: `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7`
- Validation function: `0x84ec4d0` (`0x1746` bytes)
- Successful move call: `0x84ed8a6`
- Unique move helper: `0x84ee000` (`0x1c9` bytes; one direct caller)
- PlayerAuthenticationInfo: `0x128` bytes; first/XUID field offset `0`
- Optional engaged flag: `0x128`
- Endstone chain: the patch point executes inside Endstone's call to original BDS validation, so the verified XUID is visible to Endstone's post-validation ban check.
- Release status: candidate; blockers are hook_harness_passed, human_reviewed, live_tested.
