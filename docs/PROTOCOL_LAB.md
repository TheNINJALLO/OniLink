# Protocol Lab

Protocol Lab is an owner-only semantic packet test surface inside the existing OniControl dashboard.
It defaults to disabled and never accepts packet IDs or raw bytes.

Ordinary OniControl endpoints always reject payload fields for packet IDs, raw/wire bytes, stack
IDs, memory addresses, RVAs, serializer layouts, JWTs, tokens, signatures, private keys, and shell
commands—including nested objects and arrays. Login, sub-client login, encryption, network
authentication, JWT-chain, OniForward, and credential-bearing packets are not ordinary typed actions.

Safe defaults remain:

```properties
protocolLab.enabled=false
protocolLab.allowBackendBound=false
protocolLab.maxPacketsPerMinute=30
protocolLab.maxSessionSeconds=300
protocolLab.allowedXuids=
protocolLab.allowedBackends=
```

Startup rejects an enabled gate with an empty XUID or backend allowlist. To test safely:

1. Enable OniControl and Protocol Lab, then restart OniLink.
2. Sign in as the provider Owner and open **OniControl -> Protocol Lab**.
3. Select a connected allowlisted player on an allowlisted backend.
4. Start the bounded timed session.
5. Choose a reviewed semantic model and edit its JSON fields.
6. Dry-run first. OniLink validates required fields and encodes with the player's negotiated codec.
7. Send only after the encoded size and protocol are correct.
8. Stop the session when finished; it also expires automatically.

The first reviewed clientbound models are system message, title, subtitle, actionbar, toast,
play/stop sound, and particle. Backend-bound sending fails closed because no backend-bound model has
completed semantic review. Every real send consumes the per-owner rate limit, is written to the
dashboard audit, and appears in the packet monitor with `PROTOCOL_LAB` origin. The injected packet
does not re-enter OniPacket rule evaluation.

Tenant users have no Protocol Lab route in this release. This is stricter than the optional
narrow-grant design and prevents an ordinary tenant role from reaching the test surface.
