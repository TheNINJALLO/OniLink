# BDS profile generation

`sdkgen auth-artifacts` consumes one exact executable plus independently established function/call/helper boundaries. It verifies executable sections, x64 instruction boundaries, the direct-call destination, unique caller and signature, login-string cross-references, helper field moves, optional engagement, ABI-specific structure sizes, and required offsets. It writes only minimal JSON/header/report output; no BDS code or full disassembly is distributed.

Current generated roots:

- `OniBridge/generated/bds/1.26.44.3/linux-x86_64/`
- `OniBridge/generated/bds/1.26.44.3/windows-x86_64/`

Each contains `abi.json`, `symbols.json`, `signatures.json`, `profile.json`, `report.md`, `adapter.cpp`, and `include/onibridge/bds_abi.hpp`. Candidate profiles are copied to `OniBridge/profiles/1.26.44.3/` and can be checked against the exact cached executables with:

```powershell
cd OniBridge/tools/sdkgen
python -m sdkgen validate-profile ../../profiles/1.26.44.3/linux-x86_64.json ../../../.cache/bds/1.26.44.3/linux-x86_64/extracted/bedrock_server --allow-candidate
python -m sdkgen validate-profile ../../profiles/1.26.44.3/windows-x86_64.json ../../../.cache/bds/1.26.44.3/windows-x86_64/extracted/bedrock_server.exe --allow-candidate
```

A unique signature is necessary but insufficient. Production promotion still requires a platform-executed hook harness, human review, and live BDS acceptance. Unknown hashes, sizes, bytes, destinations, architectures, Endstone versions, or profile IDs fail closed.
