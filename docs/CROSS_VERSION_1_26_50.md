# Minecraft 1.26.50 cross-version support

OniLink `0.3.0` can accept a Minecraft Bedrock `1.26.50` client using network protocol
`2192` and relay it to BDS `1.26.45.1` using protocol `2169`, or to an older supported backend.
OniBridge remains the backend authentication component; you do not install Endweave beside OniLink.

> [!CAUTION]
> `1.26.50` has not reached its final release. This implementation follows the current Preview
> `1.26.50.27` schema in the current source tree. Mojang can still change packet layouts or the
> protocol number. The Java build and codec regression suite pass, but production status requires a final-release client and
> the live checklist below.

## September 13 source audit

`0.4.0-beta.2` includes additional fixes beyond the `0.3.0` and `0.4.0-beta.1` artifacts.
Install its updated JAR to use them. They prepare the
`2192` client route to an existing `2169` or `2168` backend; no BDS or OniBridge upgrade is
required for that route. This is preview compatibility evidence, not final-client live acceptance.

The audit compared Endweave `0.5.0` and its `bedrock-protocol 0.1.0` dependency with OniLink.
Independent wire fixtures reproduced six failures before the fixes and now cover:

| Packet area | Verified behavior |
| --- | --- |
| Inventory transactions | Populated actions remove the old source markers and retain signed container IDs |
| Item-use movement input | Reads/writes the new hand byte before the item; drops that field when encoding the older backend |
| Inventory responses | Retains absent and populated optional filtered item names on both sides |
| Diagnostics | Reads/writes the optional system-category list with its presence marker |
| Container close | Recognizes the new data-driven container and drops closes for it on older backends |
| Boss bars, dimensions, cameras, sounds, entity movement | Matches independently specified target bytes, including new default fields |
| Sub-chunks | Preserves the chunk payload and every cell of both height maps while adding row lengths |

`Endweave2192ParityTest` builds expected payloads directly from the pinned upstream schema;
it does not generate expected bytes with the codec under test. `ReleaseTranslationTest` also
checks join, movement and populated scoreboards across all three `2192` routes: `1.26.40`,
the `1.26.44` hotfix dialect, and `1.26.45`. The existing codec/translation suite remains enabled.

The new hand field cannot add off-hand behavior to an older BDS build. That backend receives
the older item-use representation, matching Endweave's downgrade. Protocol `2208` is modeled
upstream but is outside Endweave's current translation table and OniLink's registered codecs.
It is explicitly rejected rather than treated as `2192`. Older clients on a `2192` backend
are outside this audit's route; a new native backend still needs its own OniBridge profile.

## Detect upstream changes before release

The `Protocol upstream watch` GitHub workflow runs daily at 06:43 UTC once this change is
on the repository's default branch with scheduled Actions enabled. It can also be dispatched
manually. It checks upstream `main` against `tools/protocol_upstreams.json`, covering the
translation engine, packet schemas, codec generator/runtime and build dependency pins.

Changed, added or removed files fail the check and produce a comparison report in the job
summary and `protocol-upstream-review` artifact. Missing history or an unavailable checkout
also fails. GitHub notification delivery depends on the repository/account Actions settings.
The check reads source; it never installs or executes upstream code or changes OniLink's registry.

To repeat it locally after fetching current upstream source (the checked-out `HEAD` is compared):

```powershell
python tools/check_protocol_upstreams.py `
  --source endweave=.cache/protocol-audit/endweave `
  --source bedrock-protocol=.cache/protocol-audit/bedrock-protocol `
  --output build/protocol-upstreams.json
```

Both checkouts must contain their reviewed commit and the current revision being checked.
Review a reported diff, update serializers and fixtures where needed, run the tests, and only
then advance the reviewed commit. A documentation-only change outside the watched paths
does not require review. The check reports possible drift, not automatic support for future versions.
The Update Center's [signed protocol packages](PROTOCOL_PACKAGES.md) provide the existing path
for delivering a later codec fix independently of a full proxy release.

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
plugin compatible with a different BDS executable. The Linux BDS 1.26.45.1 profile is
production-approved; the separate Windows profile remains candidate-only.

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

Those were the original implementation references for `1.26.50.26` / `2192`.
The September 13 audit additionally uses:

- [Endweave](https://github.com/EndstoneMC/endweave/tree/b3bc18a0cb6e56894251554781497ff85a9b672b),
  `b3bc18a0cb6e56894251554781497ff85a9b672b` (Apache-2.0).
- [bedrock-protocol v0.1.0](https://github.com/EndstoneMC/bedrock-protocol/tree/9c93e0c6e711b161df6eb1934270936b48527120),
  `9c93e0c6e711b161df6eb1934270936b48527120` (MIT), the schema Endweave pins in its build.
- [bedrock-protocol main](https://github.com/EndstoneMC/bedrock-protocol/tree/1dc4408fd949bdf1eec11daa6373dff1a986d02a),
  `1dc4408fd949bdf1eec11daa6373dff1a986d02a`. Its intervening changes add older-version coverage;
  the audited `2168`/`2169`/`2192` wire layouts are unchanged. This is the watcher baseline.

These are source-derived fixtures and comparisons, not captured final-release client traffic.
