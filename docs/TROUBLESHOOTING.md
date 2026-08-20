# Troubleshooting

OniLink and OniBridge fail closed. Diagnose the first error rather than enabling bypasses; later failures are often consequences of the first one.

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
| Unreviewed profile blocked | Install the current production Linux artifact and keep `allow_unreviewed_profile=false`; use the override only for a separately documented candidate test |
| Missing generated adapter | Build a profile-specific plugin; generic native plugins are forbidden |

### `GLIBC_x.y not found` while loading `onibridge.so`

The native plugin was built against a newer Linux userspace than the BDS container. Do not replace the container's `libc.so.6` manually; that can break the entire container. Install the current release `.so`, whose build is capped at `GLIBC_2.35`, then restart the BDS server. The current amd64 `ghcr.io/parkervcp/yolks:python_3.14` image is Debian Bookworm-based (glibc 2.36), so it can remain in place.

Confirm the container and plugin before retrying:

```bash
ldd --version | head -n 1
readelf --version-info plugins/onibridge-*.so | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -n 1
```

The first command must report glibc 2.35 or newer, and the second must not report anything newer than `GLIBC_2.35`. If the image is older than 2.35, select a maintained Debian 12/Ubuntu 22.04-or-newer image for the BDS container.

### Undefined `std::__cxx11` symbol while loading `onibridge.so`

This means the plugin mixed GCC `libstdc++` objects with Endstone's LLVM `libc++` ABI. Do not add `libstdc++.so.6` as a workaround; a process using two C++ standard libraries can fail at allocation, exception, stream, or object-lifetime boundaries. Install a release whose compatibility manifest reports `cxx_runtime_policy_passed=true`. Current release automation allows a runtime-neutral plugin or libc++ references and rejects every `libstdc++` dependency or unresolved `__cxx11`/`GLIBCXX` symbol.

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

- Confirm native `reject_direct_joins=true`.
- Confirm the backend validator loaded successfully.
- Firewall the backend UDP listener to OniLink.

Direct-join rejection is defense in depth; the firewall remains mandatory.

## OniLink rejects every player as not allow-listed

An enabled empty list denies every player by design. In the OniLink/Pterodactyl console run:

```text
allowlist status
allowlist add 2533274790000001 YourGamertag
allowlist list
```

Use the account's authenticated numeric XUID. A gamertag works for `add` only while that player is already connected, and saved labels never authorize a join. You may also use **Dashboard → Allowlist**, or temporarily set `allowlist.enabled=false` and restart. Being listed in `permissions.admins` does not bypass ingress enforcement.

## First join works, but rejoin has empty inventory

The verified XUID was not active before BDS selected storage. Stop the test and inspect the native hook/profile evidence. This is an identity failure, not a resource-pack or cache problem.

## Backend plugin command is missing

Verify the backend's `AvailableCommandsPacket` reaches the client and that `/onilink` is the only injected proxy root. OniBridge must report command packets altered as false. Review [Command compatibility](COMMAND_COMPATIBILITY.md).

## BDS acquisition asks for EULA acknowledgement

No archive is requested until the operator independently reviews the applicable terms and sets the exact environment value `MINECRAFT_EULA_ACCEPTED=TRUE`. This gate is used only by controlled acquisition/profile tooling, not normal runtime startup.

## Collecting a useful report

Include component versions, operating system, exact BDS executable hash, profile ID, Endstone version, the first relevant error, and sanitized reproduction steps. Never include forwarding secrets, complete tokens, player identifiers, private addresses, BDS binaries, worlds, or dumps.
