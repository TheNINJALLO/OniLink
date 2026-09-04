# BDS authentication ABI report: linux-x86_64

- BDS: `1.26.45.1`
- Executable SHA-256: `8ba803f23d681816495c7ac83bdba4b9cd7165a3bee5aedd18fa0c8c3d408ec2`
- Validation function: `0x84ec020` (`0x1746` bytes)
- Successful move call: `0x84ed3f6`
- Unique move helper: `0x84edb50` (`0x1c9` bytes; one direct caller)
- PlayerAuthenticationInfo: `0x128` bytes; first/XUID field offset `0`
- Optional engaged flag: `0x128`
- Endstone chain: the patch point executes inside Endstone's call to original BDS validation, so the verified XUID is visible to Endstone's post-validation ban check.
- Release status: production; the native harness, human review, and operator-approved live deployment passed.
