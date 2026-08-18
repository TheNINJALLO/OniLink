# Adding a BDS version

1. Resolve official stable metadata and review the version pair.
2. With explicit EULA acceptance, generate and commit a complete lock containing both archive and executable hashes.
3. Inspect Linux and Windows independently and generate only minimal ABI declarations.
4. Produce candidate profiles and review every acceptance gate in [BDS_PROFILE_GENERATION.md](BDS_PROFILE_GENERATION.md).
5. Run unit tests, both hook harnesses, Endstone command fixtures, and live Linux/Windows join-rejoin tests.
6. Record exact evidence in [COMPATIBILITY.md](COMPATIBILITY.md), generate the compatibility manifest/checksums, and scan all release archives for forbidden BDS-owned files.

A new metadata URL or similar version number never updates an existing profile.

