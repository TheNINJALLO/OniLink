# Changelog

## Unreleased

- Added a self-updating, checksum-verifying PTDL v2 OniLink egg, release packaging, operator documentation, and egg regression tests.
- Added OniBridge-Geyser with local OniForward verification, strict source/XUID/context binding, replay protection, and mandatory real-address restoration.
- Added a complete Linux candidate build script, export-only Docker build, GitHub Actions artifact workflow, checked Linux manifest, and configuration bundle.
- Added Geyser and Linux packaging to normal CI and release assembly.

## 0.1.0 - unreleased

- Audited and pinned the four mandatory behavioral/native references.
- Added EULA-gated, dual-platform official BDS acquisition and its security tests.
- Added minimal ELF/PE inspection, ABI-header generation, and evidence-gated profile validation.
- Defined OniForward v2 and added matching Java/C++ implementations and vectors.
- Rebranded the proxy to Java 21 and moved its public proxy command surface below `/onilink`.
- Added native identity, replay, CIDR, configuration, profile, diagnostics, and plugin foundations.
- Updated the vendored protocol build to a Java 21-compatible Lombok plugin; 397 OniLink Java tests now pass and `OniLink.jar` builds.
- Imported and verified the user-provided official BDS 1.26.44.3 archives, generated independent Linux/Windows authentication ABI evidence, and locked both executable hashes.
- Added the exact successful-authentication move-call hook, bounded login-envelope staging, local OniForward verification, native XUID substitution, direct-join rejection, and post-login comparison.
- Built candidate Linux and Windows native plugins; the Windows native protocol and executable hook-harness targets pass.
- Added recursive release archive inspection and candidate-aware compatibility manifests.
- Production promotion remains blocked by human profile review and live Linux/Windows BDS acceptance tests.
