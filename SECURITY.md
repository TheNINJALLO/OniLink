# Security policy

Report vulnerabilities privately to the repository maintainers. Do not include forwarding secrets, complete tokens, BDS binaries, player identifiers, or production addresses in an issue.

OniBridge denies identity forwarding when its runtime/profile/hash/expected-byte/chain checks are incomplete. Do not set `allow_unreviewed_profile`, `allow_unknown_bds`, or `allow_unknown_endstone` in production. Keep BDS listeners unreachable from public networks even when `reject_direct_joins` is enabled, use one 256-bit-or-stronger secret per backend, rotate with the previous-key window, and restrict secret-file permissions.

The project never accepts Minecraft server terms for an operator. BDS downloads require the exact environment value `MINECRAFT_EULA_ACCEPTED=TRUE`.

