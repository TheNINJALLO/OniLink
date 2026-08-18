# sdkgen

`sdkgen` parses x86-64 ELF and PE images, searches masked signatures only inside executable sections, validates compatibility profiles against an exact executable, and emits a deliberately small declaration header from independently verified ABI facts.

A unique byte pattern creates only a candidate. Production status additionally requires evidence for function boundaries, decoded instructions, control flow and references, a relocation-safe trampoline, calling convention and structure layouts, existing-detour/Endstone chain compatibility, the hook harness, human review, and a live BDS test. Missing evidence is a hard release blocker.

`auth-artifacts` validates the exact successful authentication move call, helper, login-string references, field moves, and optional layout; `generate-headers` and `generate-adapter` then emit the platform-specific build inputs. Install with `python -m pip install -e OniBridge/tools/sdkgen`, then run `python -m sdkgen --help`.
