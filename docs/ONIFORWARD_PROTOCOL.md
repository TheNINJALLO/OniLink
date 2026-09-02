# OniForward protocol

OniForward v3 carries a proxy-authenticated identity in the Bedrock client-data JWT claim named
`OniForward`. It is locally verified by OniBridge before BDS chooses `PlayerStorageIds`; it never
requires an HTTP callback. Version 3 replaces cross-host wall-clock freshness with signed,
single-use proxy sequences.

## Wire format

A token is `base64url(payload) + "." + base64url(HMAC-SHA256(secret, payload))`. Base64 uses the URL-safe alphabet with no padding. The exact raw payload is signed.

The payload starts with the four bytes `ONIF`, one byte encoding version (`1`), and one byte field
count (`16` for v3, `14` for legacy v2). It is followed by TLV fields in strictly increasing ID
order. Each field is `uint8 id`, `uint16 big-endian byte length`, then canonical UTF-8 bytes. Empty
values, duplicate/out-of-order IDs, missing fields, trailing bytes, malformed UTF-8, and unknown
encoding or protocol versions are rejected.

| ID | Claim | Encoding |
| ---: | --- | --- |
| 1 | `protocol_version` | unsigned decimal; current `3`, legacy `2` |
| 2 | `key_id` | UTF-8 |
| 3 | `proxy_id` | UTF-8 |
| 4 | `bridge_id` | UTF-8 |
| 5 | `backend_name` | UTF-8 |
| 6 | `session_id` | UTF-8 |
| 7 | `nonce` | UTF-8 |
| 8 | `player_name` | UTF-8 |
| 9 | `xuid` | ASCII decimal digits |
| 10 | `proxy_uuid` | lowercase canonical UUID |
| 11 | `real_ip` | IPv4 or IPv6 literal |
| 12 | `real_port` | unsigned decimal, 0..65535 |
| 13 | `issued_at_ms` | signed decimal Unix epoch milliseconds |
| 14 | `expires_at_ms` | signed decimal Unix epoch milliseconds |
| 15 | `proxy_boot_id` | v3 only; lowercase UUID containing a persisted monotonic proxy epoch |
| 16 | `sequence` | v3 only; positive unsigned decimal, increasing within the proxy boot |

The backend has one unique secret, selected by `key_id`. OniBridge accepts the active key and at most one previous key, compares signatures in constant time, and never logs either key or token. Runtime secrets come from environment variables or permission-restricted files; the illustrative secret in the public vector is not usable operationally.

## Acceptance order

OniBridge first confirms the actual peer socket against `trusted_proxy_cidrs`, then bounds the
token, decodes the canonical envelope, chooses a configured key, verifies HMAC, and validates every
claim and backend binding. For v3 it atomically consumes the signed `proxy_id + proxy_boot_id +
sequence`, then consumes `bridge_id + session_id + nonce`. Only after all checks pass may `real_ip`
or the forwarded XUID be trusted. The default signed lifetime is 5 seconds, maximum declared
lifetime 10 seconds, token size 4096 bytes, and replay capacity 10,000.

The proxy advances a persisted boot epoch at startup, embeds it in a new UUID, and increments its
sequence for each backend Login. The epoch file is stored in the configured dashboard data
directory as `oniforward-proxy.state`; the dashboard may be disabled without disabling this runtime
data directory. Persisting the epoch prevents a previously unseen old process token from rotating
the bridge backward after a proxy restart. OniBridge persists the current boot and highest sequence in
`plugins/onibridge/oniforward-sequences.state`. A small bounded window permits concurrently issued
logins to arrive out of order. Replayed sequences, retired proxy boots, and sequences at or below a
restored backend floor fail closed. Neither state file contains a secret, token, player identity,
or address.

Wall-clock timestamps remain signed for lifetime-shape validation and audit context, but v3 never
compares one provider's wall clock to the other. `proxy_clock_offset_ms` and
`allowed_clock_skew_ms` affect only legacy v2 verification. Configure `protocol = 3` to reject a
signed v2 downgrade. Existing `protocol = 2` files temporarily accept both formats so OniLink can
be upgraded before the backend is locked to v3.

The shared positive vectors are [test-vectors.json](../OniBridge/protocol/test-vectors.json). Java
and C++ unit tests assert the exact v2 and v3 tokens. Negative suites cover signature changes,
context mismatch, legacy expiration/future issuance, key rotation, v3 sequence replay, process and
backend restarts, out-of-order arrival, invalid addresses, and CIDR boundaries.
