# Feature parity

Statuses use the required vocabulary. This audit document is updated as implementation evidence is produced; `intentionally redesigned` describes a deliberate architecture change and is not a completion claim.

## Proxy reference behavior to OniLink

| Reference classes/features | OniLink replacement | Status | Audit basis |
| --- | --- | --- | --- |
| `Endlink`, `EndstoneProxy`, `BedrockProxyListener`, `ListenerSession`, `InitialClientPacketHandler` | `OniLink`, `OniLinkProxy`, listener/session/login components in `dev.onistone.onilink` | intentionally redesigned | Preserve startup/shutdown, public and trusted listeners, RakNet advertisement, Xbox login, encryption, connection accounting; remove legacy launcher and branding. |
| `AuthData`, `ClientLogin`, `ClientLoginAuthenticator`, `OfflineLoginForge`, `BedrockCrypto` | auth records, Xbox validator, backend login forge, crypto utilities | intentionally redesigned | Preserve Mojang-chain and client-JWT verification; add OniForward to every backend login. |
| `BackendConnector`, `BackendSession`, `BackendActivation`, `ProxyConnection` | backend connection coordinator and per-player connection state | intentionally redesigned | Preserve RakNet dial-out, encryption, lifecycle, bounded switch/join/failover state; bind a fresh token to each attempt. |
| `BackendDirectory`, `BackendProtocolDetector`, `JoinCandidates`, `JoinFailover` | backend catalog, protocol probe, ordered join candidates | intentionally redesigned | Preserve configured-only routing, probe fallback, immediate login rejection handling, and join failover. |
| `BackendSwitcher`, `BackendSwitchAttempt`, `BackendSwitchInputState`, `BackendSwitchReset` | switch coordinator/reset state machine | intentionally redesigned | Preserve world reset, input state, delayed packets, timeout/rollback, and safe old-session retirement. |
| `BackendFailover`, `ReconnectRoutes`, `ReconnectAddress` | failover and reconnect routing | intentionally redesigned | Preserve bounded episodes, cross-block-ID reconnect, forced destination, and route consumption. |
| `BackendInitialPacketHandler`, `BackendRelayPacketHandler`, `ClientRelayPacketHandler` | backend handshake and bidirectional relay | intentionally redesigned | Preserve packets by default; translate only supported deltas; keep command packets authoritative and transparent. |
| `ClientWorldState`, `StartGameClientFixups`, `CrossProtocolStartGameFixups` | client world-state tracker and StartGame adaptation | intentionally redesigned | Preserve cleanup packets, runtime/unique IDs, block hash scheme, dimension/respawn synchronization, and registry fixups. |
| `BackendPackFetch`, `BackendPackCache`, `ProxyResourcePackRegistry`, `ProxyResourcePackEntry` | resource-pack registry/cache/learner | intentionally redesigned | Preserve folder/archive packs, merged info/stack, chunk serving, hash verification, memory caps, and switch-time backend learning. |
| `BackendPalette`, `BackendPaletteStore`, `CrossBackendPalette`, `ItemPaletteMapping`, `EntityPalettes` | registry continuity service | intentionally redesigned | Preserve learned item/entity/block registries, stable network IDs, custom content continuity, and hashed-ID routing decisions. |
| `ProtocolRegistry`, `ProtocolBinding`, `ProtocolNegotiator`, `ProtocolNegotiation`, `CanonicalProtocol` | protocol graph and negotiation | intentionally redesigned | Preserve exact codec selection, detection, shortest adjacent path, and honest unsupported pairs. |
| `PacketTranslator`, `ChainedPacketTranslator`, `IdentityTranslator898`, `ModernClientTo898Translator`, `ModernClientTo944Translator`, `ModernClientTo975Translator`, `ModernClientTo1001Translator`, `TranslationContext`, `UnsupportedVersionPairException` | adjacent protocol translation | intentionally redesigned | Preserve safe packet/field removal and codec-driven conversion; add command-specific round-trip fixtures. |
| `AvailableCommandsInjector`, `ProxyCommandInterceptor`, `ProxyCommandRegistry`, `ProxyCommand`, `ProxyCommands` | namespaced command merger and exact interceptor | intentionally redesigned | Move to `/onilink`; backend wins collisions; retain complete backend registry. |
| `BackendCommandRouter`, `NetworkCommands`, `CommandArguments`, `CommandInterception`, `CommandSender`, `ConsoleSender`, `PlayerCommandSender`, `ProxyConsole`, `ProxyPlayerEnum` | proxy command dispatcher, console, and soft roster enum | intentionally redesigned | Preserve network operations and execution-time authorization while changing the public command namespace. |
| `BackendPermissionSync`, `ProxyPermissions`, `PermissionsConfig` | proxy permission service | intentionally redesigned | Preserve XUID/name grants, runtime persistence, restricted backend visibility, refresh after changes; do not replace backend command permissions. |
| `BackendVerificationServer`, `PendingJoinRegistry`, `PendingJoin`, `VerificationRequest`, `VerificationSigner`, `BackendVerificationConfig` | OniForward signer and per-backend configuration | intentionally redesigned | Blocking HTTP/name-keyed pending joins are removed; local signed single-use tokens replace them. |
| `ConnectedPlayerRegistry`, `ProxySessionProfile` | connection roster and codec profile | intentionally redesigned | Preserve max-player/duplicate-XUID gates and per-session client/canonical/backend codecs. |
| `ConnectionThrottle`, `PreAuthBatchLimiter`, `RateLimitReporter`, `SecurityConfig` | listener security controls | intentionally redesigned | Preserve per-IP sessions/attempts, RakNet limits/cookies, bounded batches, XUID requirement, and rate-limited reporting. |
| `CodecDefinitionState`, `NetworkSettingsNegotiator`, `NetworkSettingsNegotiationResult`, `LoggingExceptionHandler` | codec/network initialization | intentionally redesigned | Preserve definition fallbacks, compression negotiation, and explicit failure diagnostics. |
| `ProtocolFault`, `ProtocolFaultLog`, `PacketViolation`, `ProxyLogFile`, `RateLimitedSecurityLogger` | structured diagnostics/security logging | intentionally redesigned | Preserve bounded packet diagnostics without secrets/tokens. |
| `PluginManager`, `EndlinkPlugin`, `PluginContext`, `TrustedListenerSpec` | OniLink SDK addon loader/API | intentionally redesigned | Preserve isolated JAR addons, lifecycle ordering, protocol contributions, and loopback-only trusted listeners with renamed descriptor/API. |
| `BackendConfig`, `BackendKickAction`, `BackendSwitchConfig`, `CommandsConfig`, `ConfigValues`, `FailoverConfig`, `ForcedHostsConfig`, `JoinConfig`, `ProtocolFaultPolicy`, `ProxyConfig`, `ProxyPolicy` | typed OniLink properties configuration | intentionally redesigned | Preserve documented defaults/validation while adding backend-specific bridge/key/secret settings and removing legacy keys. |
| Vendored RakNet/network modules | OniLink transport modules | intentionally redesigned | Preserve source and tests with required licenses; no protocol behavior is claimed from package presence alone. |
| Vendored Bedrock protocol modules and 898/944/975/1001/2168 codecs | OniLink protocol modules | intentionally redesigned | Preserve packet models/serializers and tests; exact command round trips and supported-pair tests gate status. |
| Gradle wrapper, composite builds, CI, security and deployment assumptions | Java 21 Gradle build, CI, release and Pterodactyl docs | intentionally redesigned | Java 21 and renamed artifacts/workflows replace the reference build. |

## Backend identity reference behavior to OniBridge

| Reference variant/feature | OniBridge replacement | Status | Audit basis |
| --- | --- | --- | --- |
| Stock Python `PlayerLoginEvent` verifier | Native pre-storage verification | intentionally redesigned | Stock event is too late for native storage identity. |
| Stock Python `Player.xuid` monkey patch | Native `PlayerAuthenticationInfo`/pre-storage XUID injection | intentionally redesigned | Python property changes do not alter BDS storage selection. |
| Stock/modified `Player.address` monkey patch | `VerifiedIdentityRegistry` real endpoint lookup | intentionally redesigned | Keep native backend address untouched; expose verified real address through the service. |
| Blocking HTTP query and HMAC v1 signing | OniForward binary payload plus HMAC-SHA256 | intentionally redesigned | Local verification, bounded token, exact length-prefix encoding, key rotation, constant-time comparison. |
| Name-keyed pending join and timeout | replay cache keyed by bridge/session/nonce | intentionally redesigned | Atomic single-use, expiry pruning, hard capacity, backend/bridge binding. |
| Allowed proxy IP list | trusted proxy CIDR matcher | intentionally redesigned | Add IPv4, IPv6, mapped IPv6, host and container ranges; validate actual socket source. |
| Modified `PlayerPreLoginEvent` | standalone native hook | intentionally redesigned | Preserve timing without a patched runtime or Python dependency. |
| XUID replacement and backend UUID preservation | native identity injector with `uuid_mode=preserve_backend` | intentionally redesigned | Matches the successful reference policy and avoids UUID-indexed data splits. |
| 15-second approval plus post-login comparison | consumed session registry and post-login XUID verifier | intentionally redesigned | Disconnect and invalidate on mismatch. |
| JSON identity and operator stores | versioned identity registry and migration reader | intentionally redesigned | Legacy records never authenticate current joins. |
| Geyser extension | OniBridge-Geyser | intentionally redesigned | Preserve direct-join rejection and real-address forwarding with OniForward; no regex JSON/HTTP verification. |
| Python tests and patch integrity CI | C++ unit tests, shared protocol vectors, hook harness, integration fixtures | intentionally redesigned | Live validation remains a separate evidence category. |

## Binary evidence update

The user supplied the official Linux and Windows BDS 1.26.44.3 archives after the EULA gate. Both were imported, hashed, inspected, and used for independent ABI/call-site profiles. The Linux profile is production-approved after its human review, native harness, and operator-approved live matrix; the Windows profile remains a candidate. Both synthetic hook harnesses pass on their native CI platforms.

## Implementation evidence update

The earlier rows preserve the design decision made during the mandatory pre-implementation audit. This table records the stronger final status actually evidenced in this workspace; it does not promote a feature merely because a class was copied or created.

| Area | Status | Evidence or exact reason |
| --- | --- | --- |
| Official metadata and secure BDS acquisition | implemented and integration-tested | 26 Python tests pass; the official stable 1.26.44.3 Linux/Windows archives were imported into the isolated cache and `bdsctl verify` rechecked their hashes, formats, architectures, required files, and paired lock. |
| ELF/PE fixture inspection and candidate profile gates | implemented and unit-tested | 17 Python tests pass for formats, architecture, sections, signatures, stale hashes, ABI separation, patch length, evidence, size and offsets. |
| OniForward Java signer/verifier and backend JWT insertion | implemented and unit-tested | The Java 21 suite passes the fixed cross-language vector and backend-login integration tests. |
| OniForward C++ verifier, replay cache, CIDR matcher and identity registry | implemented and unit-tested | The Windows C++20 native unit test passes under MSVC; Linux compilation also succeeds. |
| Java proxy transport, authentication, routing, failover, switching, translation, resources, registries, permissions, addons and security controls | implemented and operator-accepted on Linux BDS | The Java 21 suite passes and the operator approved the complete Linux BDS production path. Other backend types retain separate gates. |
| `/onilink` merge/interception and backend command transparency | implemented and operator-accepted on Linux BDS | Focused Java tests pass and the Linux production acceptance includes command behavior. |
| Strict TOML, secret loading, plugin commands, diagnostics and migration service | Linux production-approved; Windows candidate | The exact Linux Endstone plugin and production adapter compile and pass; `/onibridge` uses the public command API and outgoing command packets remain unmodified. |
| Pre-storage native XUID hook on Linux | implemented and production-approved | Exact ELF hash/ABI/call/helper/signature evidence, generated production adapter, native Linux harness, and operator-approved live acceptance pass. |
| Pre-storage native XUID hook on Windows | implemented but awaiting live validation | Exact PE hash/ABI/call/helper/signature evidence and generated adapter exist; `onibridge.dll` builds and the executable synthetic hook harness passes. No live BDS join ran. |
| Minimal real-BDS ABI headers and profiles | implemented and integration-tested | Independent generated headers/profiles validate against the exact cached Linux and Windows executables. Linux is production; Windows remains a candidate. |
| Native Linux/Windows artifacts and release package | Linux production-approved; Windows candidate | The Linux manifest reports `production_ready=true` with no release blockers. No BDS-owned files are distributed. |
| OniBridge-Geyser | implemented but awaiting live validation | The extension verifies the embedded OniForward v2 claim locally at `SessionLoginEvent`, enforces trusted proxy CIDRs, XUID/name/backend binding, expiry and replay protection, and restores the signed address before Java connection. Protocol, parser, CIDR, replay, configuration, and compatibility-adapter behavior are unit-tested; a live Geyser/Floodgate join remains required. |
| Live identity persistence, policies, real IP and command acceptance | Linux production-approved; other paths pending | The operator approved the complete Linux BDS acceptance matrix. Windows and Geyser keep their separate live gates. |
