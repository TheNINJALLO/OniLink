# bdsctl

`bdsctl` resolves and securely caches official Bedrock Dedicated Server archives for OniBridge compatibility analysis. It has no runtime dependencies outside Python 3.11+.

From this directory, run `python -m bdsctl resolve`. From elsewhere, install it with `python -m pip install -e OniBridge/tools/bdsctl`, then use the same command.

No command downloads a BDS archive unless `MINECRAFT_EULA_ACCEPTED` is exactly `TRUE`. Setting that value means the operator has independently reviewed and accepted the applicable Minecraft server terms; this project never accepts them for the operator.

The stable channel is the default. Preview metadata uses different download types and is never substituted for stable metadata. `resolve` only reads metadata. `lock` resolves both platforms, downloads and validates each archive, then atomically writes complete hashes to `bds.lock.json`. `fetch`, `inspect`, and `verify` consume an existing lock and never resolve a new version.

When an operator has already downloaded both archives through the official page, `import-local --linux <zip> --windows <zip>` resolves current official metadata and applies the same EULA, ZIP, extraction, executable, architecture, hashing, and isolated-cache gates. Its metadata explicitly records user-supplied provenance; it never labels those bytes as an automated official download.

Use `import-local --lock <complete-lock> --linux <zip> --windows <zip>` to import an exact release
offline. Existing matching imports are verified and reused. Locked hashes and sizes are never
silently replaced by observed values. `--expect-version <version>` is available on `resolve`, `lock`,
`import-local`, and `verify-local` to reject an unexpected platform version.

`verify-local --lock <complete-lock> --linux <zip> --windows <zip> --output <report.json>` verifies
archive and executable hashes, package contents, and architecture directly from local ZIPs. It does
not extract files, download anything, start BDS, or claim a live native test. See the repository's
[version update guide](../../../docs/ADDING_BDS_VERSION.md).
