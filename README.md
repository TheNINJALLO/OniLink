# OniLink + OniBridge

This repository contains a Java 21 Bedrock proxy, a native C++20 Endstone identity bridge, and the OniBridge-Geyser extension for Java backends. OniLink authenticates the public Xbox client, creates an offline backend login, and embeds a short-lived HMAC-signed `OniForward` claim. OniBridge validates that claim locally before BDS selects player storage; OniBridge-Geyser validates the same claim locally before Geyser connects to Java.

The repository is deliberately fail-closed. A native plugin build is not a production compatibility claim: a production release also needs an exact BDS lock, independently generated Linux and Windows profiles, hook-harness evidence, human review, and live results. Candidate artifacts keep those missing gates visible and require the explicit non-production `allow_unreviewed_profile` setting.

Current evidence:

- Four required upstream repositories were audited and pinned in [SOURCE_AUDIT.md](docs/SOURCE_AUDIT.md).
- User-supplied official stable Linux and Windows archives were imported, verified, and locked on 2026-08-18 UTC as BDS `1.26.44.3`; BDS files remain excluded from releases.
- Independent ELF64/System V and PE32+/Microsoft x64 ABI artifacts and exact-hash candidate profiles were generated from those executables.
- The Python acquisition/SDK/package suites, Java proxy suite, native harnesses, and OniBridge-Geyser security tests are wired into CI. The Linux candidate workflow builds all three deployable components without downloading or packaging BDS.
- Candidate `onibridge.so` and `onibridge.dll` files exist. Neither profile is production-approved and no live BDS join, storage-continuity, or command-fixture result is claimed.

See [BUILDING.md](docs/BUILDING.md), [INSTALLATION.md](docs/INSTALLATION.md), and [COMPATIBILITY.md](docs/COMPATIBILITY.md).
