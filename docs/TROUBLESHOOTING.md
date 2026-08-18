# Troubleshooting

- `MINECRAFT_EULA_ACCEPTED=TRUE` error: no archive was requested. Review the applicable terms independently, then set the exact value only if accepted.
- Profile/hash mismatch: stop. Confirm the executable path and generate a separate reviewed profile; never copy an RVA from another build or OS.
- Startup shutdown: inspect the first OniBridge critical message. Missing secret, unknown TOML key, untrusted profile, expected-byte mismatch, chain uncertainty, or absent generated adapter is intentionally fatal.
- Every forwarded login rejected: align backend name, bridge ID, key ID, Base64 secret, clocks, and actual proxy source CIDR.
- First join works, rejoin has empty inventory: the hook did not change the trusted XUID before storage selection. Treat this as an identity failure, not a cache issue.
- Backend plugin command missing: verify its `AvailableCommandsPacket` arrives and `/onilink` is the only injected proxy root. OniBridge must report command packets altered as false.

