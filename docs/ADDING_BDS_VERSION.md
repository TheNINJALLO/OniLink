# Adding a BDS version

1. Inspect the new signed Endstone tag and compare every changed file with the previously pinned tag.
2. Resolve official stable metadata and reject the run unless Linux and Windows name the expected exact version.
3. With explicit EULA acceptance, download/import both archives and generate a complete lock containing archive and executable hashes.
4. Inspect Linux and Windows independently and create reviewed analysis recipes without copying offsets between platforms.
5. Generate minimal ABI declarations, adapters, reports, and candidate profiles; validate them against the exact executables.
6. Delete every cached BDS-owned input before uploading or publishing derived artifacts.
7. Run unit tests, both platform builds and hook harnesses, command fixtures, and live Linux/Windows join-rejoin tests.
8. Record exact evidence in [COMPATIBILITY.md](COMPATIBILITY.md), generate the compatibility manifest/checksums, and scan all release archives for forbidden BDS-owned files.

A new metadata URL or similar version number never updates an existing profile.
