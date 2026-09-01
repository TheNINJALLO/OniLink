# BDS authentication ABI report: windows-x86_64

- BDS: `1.26.45.1`
- Executable SHA-256: `92d09c7b74ac6a9805bafc166d8e0a13ac9e5db73dbbb0819e5a14093699d44f`
- Validation function: `0xa77c70` (`0x145c` bytes)
- Successful move call: `0xa78b02`
- Unique move helper: `0xa7a030` (`0x2d5` bytes; one direct caller)
- PlayerAuthenticationInfo: `0x180` bytes; first/XUID field offset `0`
- Optional engaged flag: `0x180`
- Endstone chain: the patch point executes inside Endstone's call to original BDS validation, so the verified XUID is visible to Endstone's post-validation ban check.
- Release status: candidate; blockers are hook_harness_passed, human_reviewed, live_tested.
