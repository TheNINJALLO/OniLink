# OniBridge

OniBridge is the C++20 Endstone side of OniForward. The shared core validates exact canonical tokens, active/previous HMAC keys, time/context claims, actual peer CIDRs, atomic replay identity, and verified identity lookups. The plugin registers only `/onibridge` through Endstone's public command API and never replaces or detours the command registry.

An exact BDS profile and generated native login adapter are mandatory. Candidate profiles require the explicit acceptance-test-only `allow_unreviewed_profile` switch; production profiles additionally require every documented evidence gate. The generic source tree shuts BDS down when an adapter is absent or mismatched.

Build and profile instructions are in [BUILDING.md](../docs/BUILDING.md) and [BDS_PROFILE_GENERATION.md](../docs/BDS_PROFILE_GENERATION.md).
