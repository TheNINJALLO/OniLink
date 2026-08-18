# Troubleshooting

OniLink and both validators fail closed. Diagnose the first error rather than enabling bypasses; later failures are often consequences of the first one.

## Native backend shuts down during startup

Read the first OniBridge critical message.

| Message/cause | Correct action |
| --- | --- |
| Missing secret | Add the configured environment variable or restricted file to the BDS process |
| Unknown TOML key | Remove the typo or unsupported key; native configuration is strict |
| Empty/wrong profile | Set the exact profile ID from [Compatibility](COMPATIBILITY.md) |
| BDS hash mismatch | Stop; use the exact supported executable or generate/review a new profile |
| Endstone mismatch | Install the exact required Endstone build |
| Expected-byte/call mismatch | Stop; do not copy an offset or RVA from another build or OS |
| Unreviewed profile blocked | Use `allow_unreviewed_profile=true` only for the documented candidate test |
| Missing generated adapter | Build a profile-specific plugin; generic native plugins are forbidden |

Never turn off `shutdown_on_hook_failure` to make an incompatible server stay online.

## Every proxied login is rejected

Compare these values character-for-character on OniLink and the backend validator:

1. Backend name
2. Bridge ID
3. Active key ID
4. Decoded secret bytes
5. Token lifetime/skew and system clocks
6. Actual proxy source CIDR observed at the backend

A Java properties value with an inline `# comment` includes the comment text. Put comments on their own lines.

## Direct backend joins succeed

Treat this as a security failure:

- Confirm native `reject_direct_joins=true` or that OniBridge-Geyser is active.
- Confirm the backend validator loaded successfully.
- Firewall the backend UDP listener to OniLink.
- Verify Geyser is not listening publicly on another interface/allocation.

Direct-join rejection is defense in depth; the firewall remains mandatory.

## First join works, but rejoin has empty inventory

The verified XUID was not active before BDS selected storage. Stop the test and inspect the native hook/profile evidence. This is an identity failure, not a resource-pack or cache problem.

## Geyser disconnects on `SubChunkRequestPacket`

Set the affected OniLink backend explicitly:

```properties
backend.java.dropSubChunkRequests=true
```

This is required when switching a Bedrock client session to a Geyser backend that does not use BDS sub-chunk semantics.

## OniBridge-Geyser rejects real-address access

The installed Geyser build changed an internal access point used before the Java connection. The extension rejects the join rather than bypassing address restoration. Use the documented Geyser target or update, test, and review the compatibility adapter.

## Backend plugin command is missing

Verify the backend's `AvailableCommandsPacket` reaches the client and that `/onilink` is the only injected proxy root. OniBridge must report command packets altered as false. Review [Command compatibility](COMMAND_COMPATIBILITY.md).

## BDS acquisition asks for EULA acknowledgement

No archive is requested until the operator independently reviews the applicable terms and sets the exact environment value `MINECRAFT_EULA_ACCEPTED=TRUE`. This gate is used only by controlled acquisition/profile tooling, not normal runtime startup.

## Collecting a useful report

Include component versions, operating system, exact BDS executable hash, profile ID, Endstone/Geyser version, the first relevant error, and sanitized reproduction steps. Never include forwarding secrets, complete tokens, player identifiers, private addresses, BDS binaries, worlds, or dumps.
