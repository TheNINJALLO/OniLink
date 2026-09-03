# Minecraft 1.26.50 cross-version preview

OniLink `0.3.0-beta.10` can accept a Minecraft Bedrock `1.26.50` client using network protocol
`2192` and relay it to BDS `1.26.45.1` using protocol `2169`, or to an older supported backend.
OniBridge remains the backend authentication component; you do not install Endweave beside OniLink.

> [!CAUTION]
> `1.26.50` has not reached its final release. This implementation follows the current Preview
> `1.26.50.26` schema. Mojang can still change packet layouts or the protocol number. The Java
> build and codec regression suite pass, but production status requires a final-release client and
> the live checklist below.

## What happens automatically

```text
Bedrock 1.26.50 client       OniLink                  BDS 1.26.45.1
protocol 2192          ->    decode + translate  ->  protocol 2169
```

OniLink negotiates each side independently. Changed fields are decoded with the 2192 codec and
re-encoded with the backend codec. Fields that do not exist on 2169 are omitted by that encoder.
The two new 2192-only packets and the new string-list resource-pack setting are dropped because
inventing an older representation would be unsafe.

Negotiation is local to each player session. A 2168 player and a 2192 player can connect through
the same running listener at the same time; a 2192 join does not change the codec of existing
sessions, restart OniLink, or restart the backend. Installing the beta JAR itself still requires
the normal one-time OniLink restart.

The backend pong supplies both its protocol and version. BDS `1.26.45.1` reports protocol `2169`.
That still matters for older servers because `1.26.40` and `1.26.44` both report protocol `2168`,
while `1.26.44` has a different SetScore removal layout. OniLink selects that hotfix codec from
version `1.26.44.x`.

The proxy also normalizes the backend Login identity envelope. A Preview client is not required to
provide the untrusted `ThirdPartyName` skin field: OniLink writes it from the Mojang-authenticated
Xbox display name and clears `ThirdPartyNameOnly` before signing the backend JWT.
This lets OniBridge bind the raw Login packet to the signed OniForward identity without trusting
client-supplied naming data.

## Configuration

Keep automatic backend detection whenever the panel/network allows the unconnected UDP ping:

```properties
backend.protocol=auto
```

For a named backend whose ping cannot be read, pin the version BDS actually runs:

```properties
backends=survival
backend.survival.host=45.143.196.160
backend.survival.port=25570
backend.survival.protocol=1.26.45
```

Do **not** put `1.26.50` in the backend field while BDS remains on `1.26.45`. `1.26.50` describes
the player-facing connection. Use `2169` or `1.26.45` only for BDS `1.26.45.x`. A raw `2168` pin
selects the newest known 2168 dialect (`1.26.44`); use the exact text `1.26.40` only for a backend
still running that release.

No OniBridge key changes are needed for protocol translation. BDS `1.26.45.1` requires the exact
matching OniBridge build/profile and Endstone `0.11.10`; protocol support does not make a native
plugin compatible with a different BDS executable. The new profile remains a candidate until its
live acceptance gate is complete.

## Required live acceptance

Before calling the route production-ready, test with the final public 1.26.50 client:

1. Server-list discovery and Xbox-authenticated join.
2. Resource-pack negotiation and spawn into existing chunks.
3. Movement, block placement/breaking, both hands, inventory moves, crafting, and containers.
4. Commands, scoreboards, boss bars, sounds, maps, and custom dimensions used by the server.
5. OniLink backend switching, disconnect/rejoin, and failover.
6. Packet monitor review with no unknown required packet, decode fault, or repeated drop.

If the final client announces a protocol other than `2192`, OniLink fails closed with the normal
outdated-server response. Capture the announced number and update the codec from the final schema;
do not alias an unknown protocol to 2192.

## Reviewed upstream evidence

- [EndstoneMC Endweave `develop`](https://github.com/EndstoneMC/endweave/tree/develop), reviewed at
  `b5974fd4570d3e627423ed8a71fcb93dc015cb0f`.
- [EndstoneMC bedrock-protocol](https://github.com/EndstoneMC/bedrock-protocol), reviewed at
  `208db02e15518522ff367b51faa281ecdbf964ee`.

Both references identify `1.26.50.26` as protocol `2192`. The commits are pinned here so a later
upstream edit cannot silently change what this implementation claims to support.
