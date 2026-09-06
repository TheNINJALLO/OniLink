# Adding a BDS version

Client protocol support can be updated in OniLink while a backend stays on its previously approved
BDS/Endstone build. Importing a new BDS archive and enabling a new native backend are separate steps:
OniBridge remains an Endstone plugin and requires an exact executable profile and compatible runtime.
An archive alone does not supply new packet serializers, runtime-ID mappings, or native hook offsets.

## Verify the supplied 1.26.45.1 set

Run from the repository root after installing the local tools once:

```powershell
python -m pip install -e OniBridge/tools/bdsctl -e OniBridge/tools/sdkgen
python -m bdsctl verify-local --lock OniBridge/bds.lock.json `
  --linux bedrock-server-Linux-1.26.45.1.zip `
  --windows bedrock-server-Windows-1.26.45.1.zip `
  --expect-version 1.26.45.1 --output build/bds-update/server-files.json
python tools/profile_pipeline.py validate-checked --lock OniBridge/bds.lock.json `
  --allow-candidate --require-production-platform linux-x86_64
```

`verify-local` checks both archives without network access, extraction, or server startup. It checks
the archive and executable SHA-256 values, sizes, package file list, CRCs, required files, and
platform architecture. The report explicitly records that this command performs no live test.
The checked-profile gate separately binds the native artifacts to those same executable hashes.

## Import files for analysis

After independently accepting the Minecraft server terms, import against the existing lock into
an isolated cache. Identical imports can be repeated; a different archive or executable is rejected.

```powershell
$env:MINECRAFT_EULA_ACCEPTED = "TRUE"
python -m bdsctl --cache .cache/bds-update import-local `
  --lock OniBridge/bds.lock.json --expect-version 1.26.45.1 `
  --linux bedrock-server-Linux-1.26.45.1.zip `
  --windows bedrock-server-Windows-1.26.45.1.zip `
  --output build/bds-update/bds.lock.json
python -m bdsctl --cache .cache/bds-update verify --lock build/bds-update/bds.lock.json
```

For a genuinely new version without a complete lock, omit `--lock` on `import-local`, replace the
archive paths and `--expect-version` with the new exact version, and keep the output in the staging
directory. This resolves official metadata and records inspected hashes. `--expect-version` rejects
a different release on either platform before import. Do not relabel old archives with new versions.
Versioned filenames are checked too; filenames alone are not proof of executable compatibility.

## Translate and test protocols independently

For 1.26.45, protocol 2169 restores the original SetScore layout; 1.26.44 uses the temporary
protocol-2168 hotfix serializer. OniLink decodes the sender's dialect, applies the registered
translation path, then encodes the receiver's dialect. Backend detection should stay on `auto`.

The vendored codec agrees with [Cloudburst's 1.26.45 codec](https://github.com/CloudburstMC/Protocol/blob/3.0/bedrock-codec/src/main/java/org/cloudburstmc/protocol/bedrock/codec/v2169/Bedrock_v2169.java).
The regression fixtures additionally exercise the 1.26.44 scoreboard difference, since an integer
protocol diff alone cannot represent that hotfix.

```powershell
java -cp OniLink/dist/OniLink.jar dev.onistone.onilink.modules.forge.ForgeCli `
  build/bds-update/protocol 1.26.44 1.26.45
```

This writes the full compatibility matrix and `protocol-diff.json`. Use release names to retain
hotfix dialects; bare protocol numbers select their configured canonical dialect. The diff lists
packet IDs, directions, and serializer changes; it does not infer semantic equivalence.

For a future protocol, add its versioned codec, register it in `CanonicalProtocol` and
`ProtocolRegistry`, implement the necessary field conversions/drop rules, and add exact wire
fixtures. The seven current `ReleaseTranslationTest` routes cover join, movement, and populated
scoreboard packets without silently skipping unencodable packets. Test commands, inventory/chunks,
resource packs, and gameplay using real clients before making a live compatibility claim.

## Enable a new native backend

1. Inspect Linux and Windows independently and create reviewed analysis recipes; never copy offsets
   between platforms or versions. If Endstone changes, audit the exact tag against the pinned build.
2. Generate minimal ABI declarations, adapters, reports, and candidate profiles with
   `tools/profile_pipeline.py generate --lock <staged-lock> --cache <staged-cache>` for the new version.
3. Validate against the exact extracted executables, build both native platforms, and run hook,
   identity-forwarding, command, join/rejoin, and player-storage tests.
4. Record exact evidence in [COMPATIBILITY.md](COMPATIBILITY.md). Windows 1.26.45.1 remains candidate
   until its own live acceptance passes; a Linux approval does not approve Windows.
5. Generate the compatibility manifest/checksums and scan release archives for forbidden BDS-owned
   inputs. Publish only derived artifacts, never proprietary server files or extraction caches.

A new metadata URL or similar version number never updates an existing profile.
