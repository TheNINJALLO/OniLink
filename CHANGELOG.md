# Changelog

## Unreleased

## 0.1.6 - 2026-08-19

- Replaced the v0.1.5 Pterodactyl Application API provisioner with a single-container tenant
  supervisor: one existing OniLink process now runs multiple isolated proxy listeners and serves
  every customer through the same authenticated control-plane URL.
- Added tenant-scoped dashboard accounts, strict cross-tenant authorization, owner-managed tenant
  suspension, per-proxy lifecycle/player/allowlist/backend controls, and private backend handoffs.
- Added per-tenant proxy configuration, forwarding keys, allowlists, caches, restart persistence,
  allocation-conflict checks, integration coverage, and a complete same-server setup guide.
- Rebuilt the embedded control plane as a typed React application with responsive navigation,
  accessible workflows, reusable components, and hashed production assets inside `OniLink.jar`.
- Added focused frontend formatting, lint, type, component, and production-build gates to local and
  GitHub Actions builds while retaining server-side role enforcement and secret redaction.
- Removed the obsolete `tenantctl` separate-server utility and its release assets.

## 0.1.5 - 2026-08-19

- Moved commercial Pterodactyl tenancy into an owner-only main control-plane page with redacted
  connection storage, customer/node/egg/allocation discovery, customer creation, reusable resource
  plans, isolated server provisioning, private handoffs, retry/reconciliation, and lifecycle state.
- Added regression coverage for full Pterodactyl discovery/provision/suspend behavior, one-allocation
  isolation, valid forwarding secrets, credential redaction, handoff contents, and restart persistence.

## 0.1.4 - 2026-08-19

- Simplified native-backend onboarding to three normal inputs: backend name, BDS allocation, and
  OniLink public IP; bridge/key labels remain available only as advanced overrides.
- Added a one-click backend setup ZIP containing the matched Endstone key, complete
  `onibridge.toml`, allocation explanation, upload paths, restart order, and validation steps.
- Added an isolated commercial-hosting provisioner that creates one Pterodactyl OniLink server and
  one primary allocation per tenant, produces a private customer handoff, and supports idempotent
  status, suspend, and unsuspend operations for verified billing workflows.
- Added a hidden, administrator-controlled first-owner setup code to the Pterodactyl egg so hosted
  tenants can receive their dashboard without exposing the code as a customer-editable variable.
- Standardized project-owned C++, Python, and dashboard sources with checked-in formatter rules and
  a CI style gate; generated and vendored source boundaries remain explicit.
- Reworked dense runtime templates and native parsing code for readability without changing the
  forwarding protocol or fail-closed validation behavior.
- Removed obsolete StartGame fixup state and shortened incident-style comments to the protocol and
  maintenance decisions they document.
- Added a contributor guide with source ownership, formatting, testing, and privacy requirements.
- Bumped OniLink, OniBridge, OniBridge-Geyser, the Pterodactyl bootstrap, tenant templates, and Linux
  release automation to `0.1.4`.

## 0.1.3 - 2026-08-19

- Promoted secure native-backend onboarding into a dedicated **Add Backend** control-plane page with direct entry points from Backends and Configuration.
- Added a three-stage guided workflow that explains the BDS endpoint, trusted OniLink source CIDR, bridge ID, and key ID without requiring raw configuration edits.
- Added an explicit post-generation view for the proxy properties already saved, the one-time backend key, the complete Endstone `onibridge.toml`, exact upload paths, restart order, and `/server` test command.
- Prevented duplicate wizard submissions while key generation and validated configuration changes are in progress.
- Expanded the dashboard API response and regression coverage for the generated non-secret OniLink properties while continuing to keep secret material out of `config.properties` and audit records.
- Updated the installation guides and Wiki to use the first-class backend workflow.
- Bumped OniLink, OniBridge, OniBridge-Geyser, the Pterodactyl bootstrap, and Linux release automation to `0.1.3`.

## 0.1.2 - 2026-08-19

- Added a persistent, fail-closed OniLink XUID allowlist enforced immediately after Xbox login-chain authentication and before session/backend allocation.
- Added administrator-only allowlist management through the proxy console, in-game commands, dashboard APIs, dashboard UI, audit records, and redacted support bundles.
- Added a Pterodactyl allowlist switch with safe-off default, detailed enrollment/recovery instructions, and complete configuration examples.
- Changed runtime updates to GitHub's latest stable release endpoint, excluding drafts and prereleases.
- Extended checksum-verified runtime updates to `start-onilink.sh` and `onilink.properties.example` while preserving live `config.properties` and rollback copies.
- Bumped OniLink, OniBridge, OniBridge-Geyser, the Pterodactyl bootstrap, and Linux release automation to `0.1.2`.

## 0.1.1 - 2026-08-19

- Promoted the exact Linux BDS `1.26.44.3` + Endstone `0.11.9` profile to production after operator approval of the human-review and live-acceptance gates.
- Regenerated the native adapter as a production profile, removed the unreviewed-profile opt-in from shipped Linux examples and the dashboard backend wizard, and made the release manifest report `production_ready=true`.
- Bumped OniLink, OniBridge, OniBridge-Geyser, the Pterodactyl bootstrap, and Linux release automation to `0.1.1`.

## 0.1.0 - 2026-08-18

- Fixed the dashboard broadcast and account-creation forms retaining `event.currentTarget` after an asynchronous request, which caused successful actions to end with `Cannot read properties of null (reading 'reset')` in browsers.
- Added a dashboard-guided BDS backend wizard that preserves existing routes, generates a unique protected secret, validates and backs up `config.properties`, and produces a matched downloadable Endstone key plus complete `onibridge.toml`.
- Added detailed multi-server, routing, Pterodactyl, manual configuration, troubleshooting, and removal guides to the documentation and Wiki.
- Updated native OniBridge to tighten selected Linux secret files to owner-only permissions automatically before reading them, while continuing to fail closed when permissions cannot be secured.
- Fixed the Linux native core to compile and link consistently with Endstone's libc++ ABI, and expanded the ELF release gate to reject mixed libstdc++ dependencies and unresolved `std::__cxx11` symbols.
- Lowered the published Linux native build baseline to Ubuntu 22.04 while retaining LLVM/libc++ 18, capped imported symbols at `GLIBC_2.35`, embedded the ABI result in the Linux manifest, and added a regression gate for older Pterodactyl runtimes.
- Added an embedded, responsive OniLink operations dashboard with first-run ownership, PBKDF2 accounts, roles, TOTP, expiring sessions, audit records, live runtime/player/backend views, safe operator actions, redacted configuration editing with validation/rollback, metrics, logs, and support bundles.
- Integrated dashboard TCP exposure and persistence into the self-updating Pterodactyl egg, with complete standalone, HTTPS, panel, backup, and recovery documentation.
- Added a self-updating, checksum-verifying PTDL v2 OniLink egg, release packaging, operator documentation, and egg regression tests.
- Added OniBridge-Geyser with local OniForward verification, strict source/XUID/context binding, replay protection, and mandatory real-address restoration.
- Added a complete Linux candidate build script, export-only Docker build, GitHub Actions artifact workflow, checked Linux manifest, and configuration bundle.
- Added Geyser and Linux packaging to normal CI and release assembly.

- Audited and pinned the four mandatory behavioral/native references.
- Added EULA-gated, dual-platform official BDS acquisition and its security tests.
- Added minimal ELF/PE inspection, ABI-header generation, and evidence-gated profile validation.
- Defined OniForward v2 and added matching Java/C++ implementations and vectors.
- Rebranded the proxy to Java 21 and moved its public proxy command surface below `/onilink`.
- Added native identity, replay, CIDR, configuration, profile, diagnostics, and plugin foundations.
- Updated the vendored protocol build to a Java 21-compatible Lombok plugin; 405 OniLink Java tests now pass and `OniLink.jar` builds.
- Imported and verified the user-provided official BDS 1.26.44.3 archives, generated independent Linux/Windows authentication ABI evidence, and locked both executable hashes.
- Added the exact successful-authentication move-call hook, bounded login-envelope staging, local OniForward verification, native XUID substitution, direct-join rejection, and post-login comparison.
- Built candidate Linux and Windows native plugins; the Windows native protocol and executable hook-harness targets pass.
- Added recursive release archive inspection and candidate-aware compatibility manifests.
- Production promotion remains blocked by human profile review and live Linux/Windows BDS acceptance tests.
