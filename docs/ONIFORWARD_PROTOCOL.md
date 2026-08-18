# OniForward protocol

OniForward v2 carries a proxy-authenticated identity in the forged Bedrock client-data JWT claim named `OniForward`. It is locally verified by OniBridge before BDS chooses `PlayerStorageIds`; it never requires an HTTP callback.

## Wire format

A token is `base64url(payload) + "." + base64url(HMAC-SHA256(secret, payload))`. Base64 uses the URL-safe alphabet with no padding. The exact raw payload is signed.

The payload starts with the four bytes `ONIF`, one byte encoding version (`1`), and one byte field count (`14`). It is followed by 14 TLV fields in strictly increasing ID order. Each field is `uint8 id`, `uint16 big-endian byte length`, then canonical UTF-8 bytes. Empty values, duplicate/out-of-order IDs, missing fields, trailing bytes, malformed UTF-8, and unknown encoding or protocol versions are rejected.

| ID | Claim | Encoding |
| ---: | --- | --- |
| 1 | `protocol_version` | unsigned decimal; currently `2` |
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

The backend has one unique secret, selected by `key_id`. OniBridge accepts the active key and at most one previous key, compares signatures in constant time, and never logs either key or token. Runtime secrets come from environment variables or permission-restricted files; the illustrative secret in the public vector is not usable operationally.

## Acceptance order

OniBridge bounds the token, decodes the canonical envelope, chooses a configured key, verifies HMAC, validates every claim and time bound, confirms the actual peer socket against `trusted_proxy_cidrs`, then atomically consumes `bridge_id + session_id + nonce`. Only after all checks pass may `real_ip` or the forwarded XUID be trusted. The default lifetime is 5 seconds, maximum lifetime 10 seconds, clock skew 2 seconds, token size 4096 bytes, and replay capacity 10,000.

The shared positive vector is [test-vectors.json](../OniBridge/protocol/test-vectors.json). Java and C++ unit tests assert its exact token. Negative suites cover signature changes, context mismatch, expiration, future issuance, rotation, replay, invalid addresses, and CIDR boundaries.

