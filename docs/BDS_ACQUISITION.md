# BDS acquisition

`bdsctl` resolves only the official Minecraft download-links service. Archive download/import is gated by `MINECRAFT_EULA_ACCEPTED=TRUE`; this variable means the operator independently reviewed and accepted the applicable server terms. There is no separate browser dialog or acceptance window.

```powershell
$env:MINECRAFT_EULA_ACCEPTED = "TRUE"
cd OniBridge/tools/bdsctl
python -m bdsctl --cache ../../../.cache import-local `
  --linux ../../../bedrock-server-1.26.45.1-linux.zip `
  --windows ../../../bedrock-server-1.26.45.1-windows.zip `
  --channel stable --output ../../bds.lock.json
python -m bdsctl --cache ../../../.cache verify --lock ../../bds.lock.json
```

Normal online operation supports `resolve`, `lock`, `fetch`, `inspect`, `verify`, `clean`, and `status`. `import-local` applies the same ZIP, path, architecture, content, and hashing checks to archives downloaded by the operator.

The current official stable pair is BDS `1.26.45.1`. Linux archive SHA-256 is `b0db86098ee418a9bb226f6f3f51ff2be36542236839375627b29aef3dfa5cda`; Windows archive SHA-256 is `b27216dd32d034f3bc5fbe3094a70d2b3cec9a9871ca3d7b46a2b8690a1c0b14`.

The tool enforces HTTPS/TLS, Microsoft/Minecraft redirect allowlisting, bounded retries/time/size, HTML rejection, ZIP central-directory and CRC checks, required files, ELF64/PE32+ x86-64 validation, traversal/absolute/symlink rejection, pre-extraction SHA-256, isolated directories, atomic metadata, and partial cleanup. Stable and preview metadata types are separate; mismatched platform versions produce independent profiles with `paired_version=false`.
