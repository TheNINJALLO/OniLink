# Source audit

Inspection date: 2026-08-18 (America/New_York)

This is an internal engineering record. The workspace contained no implementation files before this audit. Each repository below was cloned at its exact `main` HEAD, every tracked file was included in a SHA-256 inventory, and the behavior-bearing paths were inspected before product code was created.

> [!NOTE]
> OniLink is a standalone system with its own product identity. Names and URLs in this internal
> record identify independent third-party source references solely for reproducibility, provenance,
> and license compliance. They are not OniLink aliases, components, editions, or co-brands.

## Pinned references

| Reference | Inspected commit | Files | Tree inventory SHA-256 | License |
| --- | --- | ---: | --- | --- |
| <https://github.com/luibara2/endlink> | `2e242a2e84447acdcc8e85aafa5654699761ef43` | 1,676 | `f54a9d5d5f27af2f2e28a09988235f1e138a8a3d35bd4544e92ef519164aaa83` | Apache-2.0; bundled protocol/network notices retained |
| <https://github.com/luibara2/endlinkguard> | `d70886d1b6dbf7c992b35266a9337432db34c492` | 22 | `eccde75fd9dcd9fd93ed9ce867cae3aca98e6b616d429415112e7685f785f2bc` | Apache-2.0 |
| <https://github.com/EndstoneMC/endstone> | `a73f76d3725b471a6d83783166edc004804faa1b` | 1,486 | `5adb5df6541011dc97c36ec19f4dc4e1040a1910ac675532d1a8f5639181c4f3` | Apache-2.0 |
| <https://github.com/EndstoneMC/cpp-example-plugin> | `d3b19d401570b8921f125c4e9ab0d53919561bc9` | 10 | `df0d5ee83fcae172d6c8838fb8e304cb911abe109ab90b6f11ba7df7a82fdccc` | MIT |

The inventory digest is the SHA-256 of sorted lines containing each tracked file's SHA-256 and normalized path. It is an audit completeness marker, not an upstream Git tree ID.

## Supplemental dashboard reference

The public [`TheNINJALLO/NinjOS-Proxie-Edge-Fabric`](https://github.com/TheNINJALLO/NinjOS-Proxie-Edge-Fabric) repository was separately inspected at commit `2d25c346a0d4a217932434eff3a98e278bb30f7c` to understand the operator experience requested for OniLink. The audit covered its standalone Go dashboard, embedded frontend, SQLite-backed accounts, setup flow, roles, TOTP, sessions, backend/player views, configuration handling, packet tools, transfers, audit records, and service controls.

That repository is AGPL-licensed. No dashboard source, generated asset, or styling was copied into OniLink. OniLink's dashboard is an original dependency-free Java implementation under this repository's license, designed around OniLink's own runtime objects and narrower proxy responsibilities. It intentionally omits NinjOS-only gateway, companion, and host-service features that OniLink cannot truthfully provide.

## Third-party proxy behavior audit

Relevant paths:

- `src/main/java/org/endstone/proxy`: 106 application classes covering startup, Xbox login validation, forged backend login, routing, lifecycle, switching, failover, reconnect routing, packet relay, commands, permissions, resource packs, registries, rate limits, diagnostics, addons, and the legacy verification endpoint.
- `src/test`: 79 test classes across the application and vendored codecs, with 409 `@Test` methods.
- `network/transport-raknet`: 72 RakNet transport classes plus tests; `network/transport-nethernet`: 24 optional transport classes.
- `protocol/bedrock-codec`: 1,278 Java files containing packet models, serializers, codecs, and the locally maintained 898/944/975/1001/2168 protocol work.
- `protocol/bedrock-connection`, `protocol/common`, and `protocol/adventure`: session, batch, compression, utility, and text integration.
- `build.gradle.kts`, `settings.gradle.kts`, `.github/workflows/build.yml`, `config.example.properties`, `README.md`, `SECURITY.md`, `NOTICE`, and all nested build/license files.

Behavior retained for OniLink:

- Netty/RakNet public listener, Xbox-authenticated client chain and client-JWT validation, encrypted client session, offline/self-signed backend login, dynamic backend protocol probing, forced hosts, join candidates, reconnect routes, seamless switching, failover, packet relay, StartGame correction, world cleanup, entity/runtime-ID translation, resource-pack serving and learning, block/item/entity palette continuity, protocol graph translation, connection limits, graceful shutdown, and addon isolation.
- Backend `AvailableCommandsPacket`, `UpdateSoftEnumPacket`, `CommandRequestPacket`, and `CommandOutputPacket` remain authoritative. Proxy-owned commands are merged without replacing backend definitions.
- Existing protocol and transport tests are retained as regression coverage. Tests that rely on live Xbox discovery remain environment-dependent and must report skips honestly.

Behavior replaced:

- The loopback HTTP pending-join verification protocol is replaced by the local, short-lived, backend-bound OniForward token.
- Global shared verification secrets are replaced by per-backend key IDs and secrets.
- Public proxy commands move under `/onilink`; legacy top-level aliases are disabled by default and backend definitions win collisions.
- Java 17 compilation and old packages/artifact names are replaced by Java 21 and `dev.onistone.onilink`.

Security findings:

- The old verifier signs newline-delimited fields. It rejects CR/LF in inputs, but identity matching is ultimately name-based because BDS does not trust the forged XUID/UUID.
- The old verifier performs a blocking backend-to-proxy HTTP request during login and depends on a separate Python plugin.
- A single global shared secret spans all backends in the reference configuration.
- The command relay deliberately drops undecodable command output in one path and documents a command-tree re-encode loss diagnostic; both require explicit compatibility tests in OniLink.
- Backend pack caching correctly caps memory and checks advertised hashes, but all cache and archive boundaries remain security-sensitive.
- Self-signed trusted listeners are safe only while loopback-only and secret-bound.

## Third-party backend identity behavior audit

Every variant and support file was inspected:

- Stock Python: `stock/src/endlinkguard/__init__.py`, configuration, packaging, and install notes.
- Modified Python: `modified/src/endlinkguard/__init__.py`, `verification.py`, both test modules, configuration, and packaging.
- Native runtime patch: `modified-endstone/src/patches/endstone-pre-login-identity.patch`, pinned application script, and wheel-permission repair tool.
- Geyser extension: Java source, `extension.yml`, PowerShell build, and compatibility notes.
- Root and per-variant documentation, CI, security, and license files.

Behavior retained for OniBridge:

- Reject direct backend joins; verify the actual proxy socket source; verify name, XUID, UUID metadata, backend binding, freshness, and single-use state; preserve backend UUID by default; expose verified identity/address data; verify the final player XUID after construction; fail closed; retain migration readers for legacy identity/operator stores.

Behavior replaced:

- Python monkey-patching, `PlayerLoginEvent`-only verification, the blocking HTTP request, patched Endstone wheels, and the Python pre-login event are not used by OniBridge.
- An earlier development line prototyped a separately branded Java-backend integration using
  OniForward. Stable `v0.2.0` removes that integration and ships only OniLink plus native OniBridge.

Security findings:

- Stock Python changes only Python-visible `Player.xuid` and `Player.address`; it cannot alter BDS storage selection and therefore cannot restore inventory or Ender Chest identity.
- The modified Python variant has the correct timing only because the Endstone runtime itself is patched.
- The Geyser extension extracts a flat JSON response with regular expressions and performs a blocking verification request; it intentionally fails closed for identity but tolerates real-IP mutation failure.
- The modified approval cache is keyed by case-folded name and expires after 15 seconds; OniForward instead binds and atomically consumes `bridge_id + session_id + nonce`.

## Endstone audit

Target source identifies itself as Endstone 0.11.9. Relevant paths include:

- Login and identity: `src/endstone/runtime/bedrock_hooks/server_network_handler.cpp`, `src/bedrock/network/server_network_handler.{h,cpp}`, `src/bedrock/network/{base_connection_request,connection_request}.{h,cpp}`, `src/bedrock/network/packet/login_packet.h`, `src/bedrock/certificates/identity/player_authentication_info.h`, and `src/bedrock/world/level/storage/level_storage.h`.
- Hooking: `src/endstone/runtime/{hook,vtable_hook}.{h,cpp}`, platform symbol discovery, runtime initialization, and generated platform symbol tables.
- Plugins: `src/endstone/core/plugin/{cpp_plugin_loader,python_plugin_loader}.*`, `include/endstone/plugin/plugin.h`, and `include/CMakeLists.txt`.
- Commands: `src/endstone/runtime/bedrock_hooks/minecraft_commands.cpp`, `src/endstone/core/command/*`, `src/endstone/core/player.cpp`, `src/bedrock/server/commands/*`, and command packet declarations.
- Security/lifecycle: player/IP ban lists, allowlist references, player construction, permissions, operator refresh, login/join events, and plugin reload.

Observed login order:

1. Endstone's `_validateLoginPacket` detour performs its IP-ban check.
2. It calls the previously installed/original BDS `_validateLoginPacket` implementation.
3. BDS returns `std::optional<PlayerAuthenticationInfo>`; this is the first source-visible point where parsed XUID and authenticated UUID coexist.
4. Endstone performs its player ban check using the returned name, UUID, and XUID.
5. BDS continues player creation/storage selection outside the visible Endstone detour.
6. Endstone's `tryToLoadPlayer` detour calls the original BDS function first.
7. Only after that call returns does Endstone initialize its wrapper and emit `PlayerLoginEvent`.
8. `PlayerJoinEvent`, permission recalculation, and `AvailableCommandsPacket` generation occur later during first spawn.

Consequences:

- `PlayerLoginEvent` is provably too late to affect `PlayerStorageIds`, inventory, armor, offhand, Ender Chest, experience, abilities, location, or other native data loaded by `tryToLoadPlayer`.
- The reference patch changes Endstone's `_validateLoginPacket` detour so the mutable returned `PlayerAuthenticationInfo` is updated before Endstone's player ban check and before it returns to BDS.
- The patch changes XUID and display-name fields. Its Python handler deliberately preserves `authenticated_uuid`; UUID replacement is supported by the event but intentionally unused.
- The exact private BDS call that constructs `PlayerStorageIds`, and the precise inventory/Ender Chest load instructions, are not present in Endstone source. Exact BDS 1.26.44.3 binary analysis instead identified the unique successful `PlayerAuthenticationInfo` move call before `_validateLoginPacket` returns: Linux `0x84ed8a6`, Windows `0xa78b02`. This supplies the verified XUID before every later storage-selection path without guessing the storage constructor itself.

Hook-chain finding: Endstone installs its runtime detours before it loads normal plugins. OniBridge therefore patches the unique inner BDS move call, not the already-detoured `_validateLoginPacket` entry. The outer Endstone detour still calls BDS normally, receives the substituted result, and runs its player-ban logic unchanged. See `HOOK_ANALYSIS.md`.

Command finding: Endstone registers vanilla/default/plugin commands into BDS `CommandRegistry`, serializes the full registry per player, filters it by permissions, emits soft-enum updates through the registry callback, and hooks `MinecraftCommands::executeCommand` for player/console events and plugin dispatch. OniBridge must not hook any of these paths.

## Native plugin example audit

The example confirms the public plugin contract only:

- C++20 shared library loaded from `plugins/` by extension.
- Exported `init_endstone_plugin` entry point generated by `ENDSTONE_PLUGIN`.
- `onEnable`, `onDisable`, `onCommand`, event registration, logger/server access, fluent command and permission metadata, and `endstone_add_plugin` CMake integration.
- Linux builds require libc++ for BDS ABI compatibility; the loader shadow-copies plugins into `plugins/.local`.

It does not expose private BDS functions as public API and is not evidence for any hook ABI or offset.

## Tests used as parity evidence

- Proxy reference: all 79 test classes/409 test methods were inventoried; targeted tests cover login forging, routing, failover, switching, palettes, resources, commands, permissions, verification, throttling, and protocol codecs.
- Identity reference: `modified/tests/test_verification.py` and `test_plugin_identity.py` prove canonical signing, response validation, XUID-only pre-login mutation, backend UUID preservation, post-login mismatch rejection, and real-address lifetime.
- Endstone: source tests and exact runtime paths establish public API behavior; no live BDS result is inferred from source tests.
- Native example: compilation structure and loader ABI only.

The user-provided official Linux and Windows BDS 1.26.44.3 archives were imported after explicit EULA gating and their executable hashes/layouts were analyzed independently. Production profile approval, live Linux BDS, live Windows BDS, storage persistence, and live command compatibility remain unvalidated.
