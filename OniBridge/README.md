<p align="center">
  <img src="../docs/assets/banner.svg" width="100%" alt="OniLink and OniBridge">
</p>

<p align="center">
  <img alt="C++20" src="https://img.shields.io/badge/C%2B%2B-20-00599C?style=flat-square&amp;logo=cplusplus&amp;logoColor=white">
  <img alt="Endstone 0.11.9" src="https://img.shields.io/badge/Endstone-0.11.9-52b7a8?style=flat-square">
  <img alt="Exact profile" src="https://img.shields.io/badge/Compatibility-Exact%20Profile-8b7dff?style=flat-square">
</p>

# OniBridge native plugin

OniBridge is the C++20 Endstone validator for `OniForward`. It restores the verified Xbox XUID at the exact pre-storage point required for BDS player-data continuity.

## Security properties

- Canonical HMAC token validation with active and optional previous keys.
- Backend, bridge, player, XUID, time, and actual peer-CIDR binding.
- Atomic replay consumption and bounded identity staging.
- Exact BDS executable, Endstone version, call-site bytes, and profile checks.
- Post-login XUID verification and shutdown on hook failure.
- One native `/onibridge` namespace without detouring the backend command registry.

## Compatibility contract

An exact BDS profile and generated native login adapter are mandatory. Candidate profiles require the explicit acceptance-test-only `allow_unreviewed_profile` switch. Production promotion requires every documented review, harness, lifecycle, and live acceptance gate.

The plugin intentionally shuts BDS down when its configuration, secret, adapter, runtime, or hook evidence is absent or mismatched.

## Documentation

- [Installation](../docs/INSTALLATION.md)
- [Configuration](../docs/CONFIGURATION.md)
- [Compatibility](../docs/COMPATIBILITY.md)
- [Building](../docs/BUILDING.md)
- [BDS profile generation](../docs/BDS_PROFILE_GENERATION.md)
- [Hook analysis](../docs/HOOK_ANALYSIS.md)
