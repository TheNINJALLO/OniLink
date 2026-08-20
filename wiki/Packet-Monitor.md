# Packet Monitor

OniLink `0.2.0-beta.2` includes a live packet monitor for cross-version testing. The active beta
line captures decoded packet bodies, chat, XUIDs, player/backend endpoints, and exact uncompressed
incoming bytes while always redacting authentication tokens. Open **Packet Monitor** in the normal
dashboard, choose a proxy when applicable, then join and exercise the client feature you are
investigating.

## Reading the results

| Result | Meaning |
| --- | --- |
| Native match | Both sides are already on the same compiled protocol. |
| Auto matched | Both codecs share the packet model; the target codec writes the target ID/layout. |
| Translated | A reviewed OniLink translator changed or consumed the packet. |
| Review required | The target definition is missing or the result cannot be encoded safely. |
| Unknown packet | The source codec could not decode a known model. |

An **ID-only candidate** is only a clue for maintainers. OniLink never assumes that equal numeric
IDs have equal fields or meaning.

Use the direction, result, and search filters to isolate a behavior. Use the protocol-pair selectors
to compare every compiled packet definition, select a packet name to inspect its full details, and
use **Export full capture** to save the filtered evidence. The comparison does not change live
routing.

## Privacy and limits

- Up to 5,000 records and 64 MiB of content are kept in memory per proxy and disappear on restart.
- Movement records are sampled one in 20; aggregate counts continue to update.
- Normal records include payloads, chat, XUIDs, addresses, and raw uncompressed packet bytes.
- Login/handshake authentication bodies are omitted. Token-shaped values found elsewhere are
  redacted and that record's raw bytes are withheld so the token cannot be recovered.
- Full exports contain sensitive player and network data. Support bundles use a separate
  metadata-only snapshot with identity and packet contents removed.
- Tenant logins can inspect only their assigned proxies.

The monitor helps maintainers focus protocol work using packets seen in real scenarios. It does not
learn packet semantics, make unknown protocols supported automatically, or replace live acceptance
testing. See the [complete packet-monitor guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/PACKET_MONITOR.md)
for the adjacent-version workflow and beta limitations.
