# OniPacket

OniPacket is the deterministic per-player packet engine in the real relay path.

```text
client decode -> pre-translation rules -> translator -> post-translation rules -> backend
backend decode -> pre-translation rules -> translator -> post-translation rules -> client
```

Rules are immutable snapshots replaced atomically after schema validation. Evaluation performs no
disk, HTTP, AI, control-channel, reflection, script, or shell work. Decisions are `PASS`, `DROP`,
`REPLACE`, `INJECT_BEFORE`, `INJECT_AFTER`, and `CONSUME`; consume may include typed synthetic
responses. Injected packets carry their origin and do not recursively re-enter rules unless the rule
explicitly opts in. Rule and injection counts are bounded by configuration.

Rules live under `control.dataDirectory` in a tenant/proxy-scoped path. Saves use a temporary file,
atomic replacement where supported, and a backup. A tenant runtime cannot load or replace another
tenant's document.

## Version-aware construction

The packet factory selects the target player's compiled codec, verifies the packet model exists,
validates allowlisted fields, dry-runs encoding, and caps encoded output. Backend-bound construction
uses the selected backend codec. It never copies a numeric packet ID from another protocol.
Unsupported models return `UNSUPPORTED`; failed encoding is counted and sends nothing.

Normal operators cannot enter packet IDs, wire bytes, runtime stack IDs, serializer layouts, or
memory addresses. The existing packet monitor records the source/translated packet, direction,
origin, and OniPacket action. Metrics contain only aggregate action counts—never player identifiers.

## Rule document

The dashboard exports/imports a versioned typed JSON document. Conditions may select tenant/proxy,
backend, direction, stage, origin, phase, client/backend protocol, dimension, packet model, and an
authenticated XUID or connection ID. Actions use reviewed semantic packet templates. Unknown fields
or packet actions reject the entire replacement; a partial document is never activated. Coordinate
regions and arbitrary decoded-field predicates are not implemented in this version.
The dashboard export includes in-memory `matchCount` and `lastMatched` statistics for each active
rule. Those counters are informational and are stripped before the rule document is persisted.

Defaults:

```properties
packetRules.enabled=false
packetRules.maxRules=500
packetRules.maxInjectedPacketsPerDecision=16
```

With rules disabled, the relay performs one null/runtime gate and follows the existing path.
