# BDS acquisition

`bdsctl` resolves only the official Minecraft download-links service. Archive download/import is gated by `MINECRAFT_EULA_ACCEPTED=TRUE`; this variable means the operator independently reviewed and accepted the applicable server terms. There is no separate browser dialog or acceptance window.

```powershell
$env:MINECRAFT_EULA_ACCEPTED = "TRUE"
cd OniBridge/tools/bdsctl
python -m bdsctl --cache ../../../.cache/bds-update import-local `
  --linux ../../../bedrock-server-Linux-1.26.45.1.zip `
  --windows ../../../bedrock-server-Windows-1.26.45.1.zip `
  --lock ../../bds.lock.json --expect-version 1.26.45.1 `
  --channel stable --output ../../../build/bds-update/bds.lock.json
python -m bdsctl --cache ../../../.cache/bds-update verify --lock ../../../build/bds-update/bds.lock.json
```

Normal online operation supports `resolve`, `lock`, `fetch`, `inspect`, `verify`, `clean`, and `status`. `import-local` applies the same ZIP, path, architecture, content, and hashing checks to archives downloaded by the operator.

`verify-local --lock <lock> --linux <zip> --windows <zip>` checks a complete local set without
network access, extraction, or starting BDS. `import-local --lock <lock>` reuses a complete hash
lock offline; identical cache entries can be imported again. Without `--lock`, import resolves
current official metadata. Add `--expect-version` to reject metadata drift. The full staged workflow
is in [Adding a BDS version](ADDING_BDS_VERSION.md).

The current official stable pair is BDS `1.26.45.1`. Linux archive SHA-256 is `b0db86098ee418a9bb226f6f3f51ff2be36542236839375627b29aef3dfa5cda`; Windows archive SHA-256 is `b27216dd32d034f3bc5fbe3094a70d2b3cec9a9871ca3d7b46a2b8690a1c0b14`.

The tool enforces HTTPS/TLS, Microsoft/Minecraft redirect allowlisting, bounded retries/time/size, HTML rejection, ZIP central-directory and CRC checks, required files, ELF64/PE32+ x86-64 validation, traversal/absolute/symlink rejection, pre-extraction SHA-256, isolated directories, atomic metadata, and partial cleanup. Stable and preview metadata types are separate; mismatched platform versions produce independent profiles with `paired_version=false`.
