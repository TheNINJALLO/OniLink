# Endstone 0.11.10 update audit

This audit compares the signed Endstone tags `v0.11.9` and `v0.11.10` and records the changes that
affect OniLink and OniBridge. The inspected commits are:

- `v0.11.9`: `a73f76d3725b471a6d83783166edc004804faa1b`
- `v0.11.10`: `8f84d6f5b556916597ed5b6b71329b2ed3ca8fc8`

## Verified upstream artifacts

| Artifact | SHA-256 |
| --- | --- |
| `endstone-0.11.10-linux-x86_64.zip` | `ba9cde8efe216ee72fbdac8e585e509b42dc8e12915067e6e1031fa28728ce34` |
| `endstone-0.11.10-windows-x86_64.zip` | `77826cc463ff84ecce34edad56e90d250220edc3105b5c762fee3b010156936a` |

Endstone's BDS metadata identifies the exact server build as `1.26.45.1`. The official archive
hashes are `b0db86098ee418a9bb226f6f3f51ff2be36542236839375627b29aef3dfa5cda` for Linux and
`b27216dd32d034f3bc5fbe3094a70d2b3cec9a9871ca3d7b46a2b8690a1c0b14` for Windows. Executable
hashes, sizes, and hook locations are recorded separately by the generated exact BDS profiles.

## Compatibility findings

| Area | Upstream change | OniLink/OniBridge impact |
| --- | --- | --- |
| Network protocol | BDS advances from `2168` to `2169`. | OniLink needs a distinct 1.26.45 codec and a route to 2169 backends. |
| SetScore | 1.26.45 removes the extra removal discriminator introduced only in 1.26.44. | The 2169 codec uses the original 2168 serializer; authenticated 1.26.44 clients keep their hotfix dialect. |
| Older clients | Endstone rewrites 2168 RequestNetworkSettings and Login versions to 2169 before BDS reads them. | OniLink permits a controlled 2168-to-2169 upgrade edge, matching Endstone's supported range. |
| Login internals | The Linux `_validateLoginPacket` symbol moved and multiple native symbols changed on both platforms. | The 1.26.44 native adapter cannot be reused. New exact Linux and Windows profiles are mandatory. |
| Public C++ plugin API | No installed public header changed; only `include/CMakeLists.txt` advances the API package version to 0.11.10. | OniBridge remains source-compatible but is rebuilt against the exact new tag. |
| IPv6 | New `[network] ipv6` setting defaults to `false`. | IPv4-only containers need no action. Operators using `server-portv6` must explicitly enable it. |
| Drop cancellation | Endstone restores a main-hand item when `PlayerDropItemEvent` cancels its drop. | No OniBridge change is required. |
| Runtime dependencies | Endstone runtime now links `tomlplusplus` to read `endstone.toml`. | Endstone bundles the dependency; OniBridge's public plugin build interface is unchanged. |

The symbol-table update replaced 62 lines in the Linux map and 17 lines in the Windows map. Exact
profile validation therefore checks the full executable hash, size, architecture, call bytes,
direct-call destination, structure layout, and profile identifier instead of assuming that an old
relative offset remains safe.

## Endstone configuration

IPv6 remains disabled by default. To restore dual-stack binding, add this to `endstone.toml`:

```toml
[network]
ipv6 = true
```

This controls Endstone/BDS listener binding only. It does not change the OniLink listener or an
individual backend's address in `onilink.properties`.

## Release gate

Fresh profiles start as candidates. Static binary validation and the common hook harness can prove
the adapter matches the downloaded executable, but production promotion still requires a real
Endstone 0.11.10 + BDS 1.26.45.1 startup, successful proxied login, verified XUID observation, clean
player disconnect, and clean plugin/server shutdown on each promoted platform.
