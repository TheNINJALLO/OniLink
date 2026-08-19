# Compatibility and Testing

## Exact candidate targets

| Target | Evidence | Remaining gate |
| --- | --- | --- |
| Linux BDS `1.26.44.3` + Endstone `0.11.9` | Native Ubuntu 22.04 build, maximum `GLIBC_2.35` import, C++ unit tests, synthetic hook harness | Human profile review and live BDS matrix |
| Windows BDS `1.26.44.3` + Endstone `0.11.9` | Native tests and offline plugin/hook lifecycle | Live client/storage matrix |
| Geyser `2.11` | Java protocol/security tests | Live Geyser/Floodgate matrix |

## Required live matrix

- Valid proxy join.
- Direct, unsigned, tampered, expired, replayed, and untrusted join rejection.
- Two-player concurrency.
- Restart/rejoin inventory and Ender Chest continuity.
- Bans, allowlist, operators, permissions, and real address.
- Backend command aliases, overloads, enums, completion, and output.
- Backend switching and protocol translation.
- Clean shutdown/unload.

Do not infer compatibility from a successful compile. Every BDS executable hash, platform ABI, Endstone build, expected call byte sequence, and profile ID must match.

See the exact [compatibility table](https://github.com/TheNINJALLO/OniLink/blob/main/docs/COMPATIBILITY.md) and [testing evidence](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TESTING.md).
