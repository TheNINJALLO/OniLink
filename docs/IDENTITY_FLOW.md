# Identity flow

## OniForward path

```text
Xbox-authenticated client
  -> OniLink validates the Mojang-signed identity chain and client JWT
  -> OniLink selects one configured backend
  -> OniLink creates a fresh session ID and nonce for this connection attempt
  -> OniLink signs a short-lived, backend- and bridge-bound OniForward token
  -> token is embedded in the forged backend client-data JWT
  -> Endstone PacketReceiveEvent exposes the final Login payload and actual socket source
  -> OniBridge parses the bounded client-data JWT and verifies source/token locally
  -> one short-lived identity is staged by case-folded player name
  -> replay identity bridge_id + session_id + nonce is atomically consumed
  -> the exact BDS authentication-result move call consumes the stage and replaces XUID
  -> the untouched original move helper creates optional<PlayerAuthenticationInfo>
  -> normal Endstone/BDS login continues
  -> BDS selects PlayerStorageIds and loads native player data
  -> OniBridge verifies the constructed player's XUID matches
```

A new token is required for initial connection, switching, reconnect, and failover. Tokens are never reused between backend sessions.

## Identity fields

| Field | Authority | Policy |
| --- | --- | --- |
| player name | Xbox-signed client identity plus token binding | case-insensitive equality with backend login name |
| XUID | Xbox-signed client identity, carried in OniForward | restored before storage selection; ASCII digits only |
| backend UUID | BDS/Endstone | preserved by default |
| proxy UUID | Xbox login identity | retained as metadata; not substituted for backend UUID by default |
| real IP/port | public client socket observed by OniLink | trusted only after token signature and backend socket-source checks succeed |
| backend/bridge | selected configuration | exact token binding required |

Changing backend UUID by default would split Endstone permissions, scoreboards, Script API identity, plugin databases, and UUID-indexed operator data. The default is therefore XUID restoration with backend UUID preservation.

## Why stock Endstone events are insufficient

`PlayerLoginEvent` is emitted only after Endstone calls the original `tryToLoadPlayer`. At that point native player data has already been selected/loaded. Changing a Python-visible property cannot retroactively select a different record.

The stock Python reference only monkey-patches the Python descriptor for `Player.xuid` and `Player.address`. It provides plugin-facing compatibility but does not mutate native `PlayerAuthenticationInfo`, `PlayerStorageIds`, or the BDS player record.

The modified Python reference succeeds only because a custom Endstone runtime adds a synchronous event inside its `_validateLoginPacket` detour and writes event values back to the native authentication object. OniBridge must reproduce the timing through a standalone validated BDS hook, not by requiring that patch.

## Availability timeline

| Moment | XUID state | Storage state |
| --- | --- | --- |
| Public client login at OniLink | verified Xbox XUID available | no backend state |
| Forged offline backend login | XUID present in OniForward; BDS may ignore self-signed auth XUID | no storage selected |
| BDS 1.26.44.3 successful-auth move call | `PlayerAuthenticationInfo` exists; offline XUID may be blank | selected RVAs `0x84ed8a6` (Linux) / `0xa78b02` (Windows), before return to Endstone |
| Validated OniBridge injection | verified XUID replaces the native auth XUID; backend UUID remains untouched | precedes Endstone player-ban check and later `PlayerStorageIds` selection |
| BDS player construction/`tryToLoadPlayer` | verified XUID must already be active | inventory and other native data are selected/loaded |
| Endstone `PlayerLoginEvent` | final native XUID can be checked | too late to change selection |
| Endstone `PlayerJoinEvent` | final identity visible | permissions/commands refresh |

## Failure behavior

Missing token, malformed token, unknown key, bad signature, wrong backend/bridge/name/XUID, untrusted socket source, expiry, future issue time, replay, invalid UUID/IP/port, profile mismatch, hook failure, or post-login XUID mismatch all fail closed. No remote verification request occurs during backend login.

## Legacy data

Migration code may read legacy identity and operator stores after successful native verification. A legacy record never authenticates a connection and never overrides a current signed token. Address records remain live-session-only.
