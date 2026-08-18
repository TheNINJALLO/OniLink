# BDS authentication ABI report: windows-x86_64

- BDS: `1.26.44.3`
- Executable SHA-256: `2d6518ddd25211aa51155fc015cd0393b29b2af74551a378b16f9a724ed771bd`
- Validation function: `0xa77c70` (`0x145c` bytes)
- Successful move call: `0xa78b02`
- Unique move helper: `0xa7a030` (`0x2d5` bytes; one direct caller)
- PlayerAuthenticationInfo: `0x180` bytes; first/XUID field offset `0`
- Optional engaged flag: `0x180`
- Endstone chain: the patch point executes inside Endstone's call to original BDS validation, so the verified XUID is visible to Endstone's post-validation ban check.
- Release status: candidate; the Windows hook harness passed; blockers are human_reviewed and live_tested.
