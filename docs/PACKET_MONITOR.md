# Packet monitor and cross-version matching

OniLink `0.2.0-beta.2` adds a live packet monitor to the existing control plane. The active beta
line observes the real relay path, retains detailed token-redacted packet evidence, shows how each
decoded Bedrock packet maps between the client and backend codecs, and highlights gaps that need
deliberate translator work.

The monitor assists compatibility work; it does not learn or apply packet semantics from live
traffic. A numeric packet ID alone is never enough evidence to rewrite a packet safely.

## Open the monitor

1. Sign in to the normal OniLink dashboard.
2. Select **Packet Monitor** in the left navigation.
3. If you are an owner or tenant, choose the proxy to inspect.
4. Join that proxy with the client version you want to test.
5. Exercise the behavior being investigated, such as joining, opening inventory, moving, using an
   item, changing dimension, or transferring between backends.

The live view refreshes every 1.5 seconds. **Pause live view** stops browser refreshes while you
inspect a record; it does not stop the proxy or the in-memory monitor. Select a packet name to load
its decoded source model, translated target model, player identity, endpoints, and incoming byte
preview. **Export full capture** downloads the currently filtered records with their complete
Base64 packet bytes and the compiled protocol catalog as JSON.

## What each result means

| Result | Meaning | Operator action |
| --- | --- | --- |
| Native match | Source and target use the same compiled protocol and packet model. | None. |
| Auto matched | Both compiled codecs know the same shared packet model. The target codec writes its own packet ID and layout. | Confirm the feature behaves correctly in a real client test. |
| Translated | OniLink's explicit version translator changed or intentionally consumed the packet. | Verify the intended behavior and keep a regression fixture. |
| Review required | The source packet model has no target definition, or a translation result cannot be encoded by the target. | Inspect the protocol delta and implement a reviewed translator before claiming support. |
| Unknown packet | The source codec could not decode a known packet model. | Capture protocol-dumper evidence in a controlled test and update the codec definition. |

When a missing target happens to reuse the same numeric ID, the dashboard shows an **ID-only
candidate**. This is a research hint only. Bedrock can reuse an ID for a different packet or change
field order, types, conditions, enums, or meaning between versions. OniLink never automatically
applies that candidate.

## How automatic matching helps translation

OniLink decodes incoming traffic into a shared Java packet model. For every packet on a
cross-version route, the monitor compares that model with the target codec:

```text
incoming wire packet
        |
        v
source codec -> shared packet model -> reviewed translator -> target codec -> outgoing wire packet
      |                     |                         |
      +-- incoming bytes ---+-- decoded fields -------+-- translated fields
                            |
                            +-- token-redacted in-memory packet record
```

If the shared model exists in both codecs, the target codec automatically supplies the correct
target packet ID and serializer. This handles the large unchanged portion of an adjacent-version
protocol without maintaining a manual ID table. The live count then proves that the mapping was
actually exercised, rather than merely present in source code.

If the model is absent, the monitor groups real observations by packet, direction, source protocol,
target protocol, outcome, and action. That gives maintainers a small evidence-driven review list
instead of guessing which entries in the full packet catalog matter to players.

## Workflow for a new Bedrock protocol

1. Add the new codec definitions from reviewed protocol documentation and controlled dumper output.
2. Add the adjacent protocol edge to `ProtocolRegistry`; do not advertise a route until the edge is
   present.
3. Start a test proxy with the new client protocol and the supported backend protocol.
4. Open **Packet Monitor**, select that client/backend pair, and confirm **Route available**.
5. Run a repeatable test matrix: login, resource packs, spawn, movement, inventory, blocks, items,
   entities, commands, forms, dimensions, death/respawn, and server transfer.
6. Export a report after each scenario. Prioritize `review_required` and `unknown_packet` entries
   that were observed live.
7. Compare each gap with authoritative protocol evidence. Add the smallest explicit translator and
   a fixture that verifies both directions.
8. Repeat until the tested scenarios contain no unexplained review-required observations, then run
   the complete automated and live compatibility gates.

The catalog selectors can compare any two codecs compiled into the running JAR. **Seen live** is
zero when a definition exists but the selected route has not exercised that packet.

## Filters and reports

- **Direction** separates player-to-server from server-to-player traffic.
- **Match result** isolates automatic, explicit, missing, or unknown cases.
- **Search** matches packet model, decoded/translated content, player display name, XUID, player or
  backend address, backend name, action, or review suggestion.
- **Records shown** limits the returned recent-event list; it does not change the server-side ring
  capacity.
- **Client/backend protocol** changes the static compiled catalog comparison. It does not reroute a
  live player.

Full capture exports can contain chat, commands, player display names, authenticated XUIDs, network
addresses, gameplay state, resource-pack data, and recoverable packet bytes. Treat them as
sensitive operational data, inspect them before sharing, and remove them when an investigation is
complete. Authentication tokens remain redacted even in a full export.

## Storage, sampling, and privacy

The monitor is always bounded and memory-only:

- At most 5,000 recent records and 64 MiB of captured content are retained per running proxy.
- Older records are evicted automatically when either boundary is reached, and all records
  disappear on restart. Override the byte boundary only when necessary with
  `-Donilink.packetMonitor.maxStoredBytes=<bytes>`.
- High-volume movement packet types retain one of every 20 records while their aggregate counts
  continue to increase.
- Normal records include decoded packet bodies, chat content, authenticated XUIDs, player/backend
  endpoints, and exact uncompressed incoming packet bytes. The live list omits the large bodies;
  opening one record loads them on demand.
- `LoginPacket`, `SubClientLoginPacket`, and token-bearing handshake bodies cross a hard redaction
  boundary: their authentication payload and incoming bytes are never stored. JWT-, Bearer-, and
  labeled token-shaped values detected in other packets are removed, and their raw bytes are
  withheld because byte storage would make the token recoverable.
- Support bundles include a deliberately metadata-only `packet-monitor.json`; decoded content,
  XUIDs, player names, addresses, and wire bytes are omitted from that file.

Tenant requests are resolved through the existing scoped runtime authorization. A tenant can query
only its assigned proxy runtimes; cross-tenant packet-monitor requests are rejected server-side.

## Beta boundary

`0.2.0-beta.2` is intended to validate the monitor, packet catalog, reporting workflow, and existing
cross-version codecs under real traffic. It does not turn an unknown Bedrock protocol into a
supported protocol by observation alone, infer changed field semantics, replay captured payloads,
or bypass the normal compatibility and live acceptance gates.

Report incorrect matches with the minimum useful excerpt, the two protocol numbers, the exact
player action, and the resulting client/backend behavior. Never attach full captures,
authentication chains, secrets, chat, player identifiers, addresses, or raw player data to a public
issue.
