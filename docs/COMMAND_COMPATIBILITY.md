# Command compatibility

## Endstone command path

The inspected Endstone 0.11.9 path is:

1. Vanilla commands live in BDS `CommandRegistry`.
2. Endstone creates default and plugin commands in `EndstoneCommandMap`.
3. C++ and Python plugin descriptions are loaded, commands/aliases/usages/permissions are registered into the same BDS registry, and overloads use `MinecraftCommandAdapter`.
4. `EndstonePlayer::updateCommands` calls `CommandRegistry::serializeAvailableCommands`, filters commands and semantic constraints for that player's permissions, then sends the packet.
5. `CommandRegistry::setSoftEnumValues` and `addSoftEnumValues` create `UpdateSoftEnumPacket` through the normal network update callback.
6. BDS decodes `CommandRequestPacket` and calls `MinecraftCommands::executeCommand`.
7. Endstone's detour emits `PlayerCommandEvent` or `ServerCommandEvent`, dispatches plugin/vanilla commands, and falls back to the previous/original implementation for unsupported origin types.
8. BDS/Endstone `CommandOutput` is serialized back as `CommandOutputPacket`.

OniBridge registers only its namespaced commands through `ENDSTONE_PLUGIN`. It does not hook registry serialization, soft enums, command requests, execution, permissions, console dispatch, or command output. Read-only `PacketSendEvent` monitors count packet IDs 76 (`AvailableCommands`) and 114 (`UpdateSoftEnum`) for `/onibridge command-status`; they never cancel or replace payloads.

## OniLink packet path

| Packet | Required handling |
| --- | --- |
| `AvailableCommandsPacket` | Decode the complete backend registry; preserve names, descriptions, aliases, overloads, enums, soft enums, chained subcommands, parameter flags/options, permission levels, command flags, and protocol fields; merge `/onilink` without damaging backend indices; re-encode the complete registry. |
| `UpdateSoftEnumPacket` | Relay add/remove/replace with enum identity and values intact; translate only for a real protocol difference; discard stale state on backend switch. |
| `CommandRequestPacket` | Consume only an exact OniLink-owned command; otherwise forward command text, slash, UTF-8, quotes, escapes, selectors, coordinates, origin data, request ID, internal/version fields, and flags without tokenizing or reconstruction. |
| `CommandOutputPacket` | Relay output type, success count, request ID/origin association, messages, parameters, data/JSON payloads, and protocol fields without suppressing plugin output. |

The current backend is authoritative. Switching removes the previous registry and enum state, waits for the new backend registry, merges only OniLink-owned definitions, and applies later soft-enum updates from the new backend.

## Collision policy

Primary proxy commands live below `/onilink`. Legacy top-level aliases default off. If optional aliases are enabled, a backend command with the same case-insensitive name wins and OniLink logs the collision. An Endstone plugin command must never become unreachable because of a proxy alias.

## Reference gaps that require tests

- The proxy reference mutates the decoded command packet in place and notes a known historical re-encode loss around the protocol-898 serializer. OniLink must prove round-trip preservation with backend-only fixtures rather than infer parity from class names.
- The proxy reference suppresses internal/empty command preview requests. OniLink must distinguish previews without consuming legitimate plugin commands.
- The proxy reference drops undecodable command output to avoid a client disconnect. Production support requires an exact codec or an explicit unsupported protocol/type result; silent plugin-output loss is not acceptable.
- Adjacent-version translators return command trees unchanged and rely on per-version codecs. Cross-version fixtures must cover every supported parameter type, especially enums, soft enums, selectors, items, blocks, entity types, JSON, messages, and raw text.

## Acceptance fixtures

Python and C++ Endstone fixture plugins must cover simple and namespaced commands, aliases, permission-gated/operator-only commands, overloads, integer/float/boolean/string/message/raw-text/JSON arguments, player/entity selectors, positions, block/item/entity-type arguments, hard/soft enums, dynamic soft-enum updates, subcommands, Unicode/quoted input, and structured output.

Tests must exercise registration before and after join, permission recalculation, plugin reload/disable/enable, `/op`, `/deop`, and backend switching. Execution and client-visible metadata are separate assertions.

## Evidence labels

Unit round trips, integration fixture runs, hook-harness transparency, live Linux BDS, and live Windows BDS are reported separately. Source inspection alone does not prove command compatibility.
