# Hook analysis

## Required timing

The verified XUID must be active after BDS parses the Login packet but before BDS selects `PlayerStorageIds`. `PlayerLoginEvent` occurs after the original `tryToLoadPlayer` call and is too late to select inventory, Ender Chest, armor, offhand, experience, abilities, location, or the rest of the native record.

Endstone 0.11.9 wraps `ServerNetworkHandler::_validateLoginPacket`: it checks the proxy-facing IP ban, calls the original BDS function, then applies its player-ban check to the returned `PlayerAuthenticationInfo`. BDS later constructs/loads the player. A detour outside Endstone would receive the result after Endstone's player-ban check, so OniBridge does not detour that outer entry.

## Selected exact targets

Both BDS 1.26.44.3 executables contain one successful authentication-result move call inside the original validation function. Its callee moves the complete `PlayerAuthenticationInfo` into `std::optional`, sets the engagement byte, and has exactly one direct caller. The call executes after successful login parsing and before the result returns to Endstone or reaches BDS player storage selection.

| Platform | Validation function | Selected call | Original helper | Exact target bytes |
| --- | --- | --- | --- | --- |
| Linux x86-64 | RVA `0x84ec4d0`, size `0x1746` | RVA `0x84ed8a6` | RVA `0x84ee000`, size `0x1c9` | `E8 55 07 00 00 80 BC 24 80 00 00 00 00` |
| Windows x86-64 | RVA `0xa77c70`, size `0x145c` | RVA `0xa78b02` | RVA `0xa7a030`, size `0x2d5` | `E8 29 15 00 00 E9 99 01 00 00` |

The signatures are unique in their executable sections. Login JSON string cross-references for `AuthenticationType`, `ThirdPartyNameOnly`, XID, and Realms fields also fall inside the selected validation functions. The helpers' field moves independently establish the layouts:

| ABI fact | Linux/System V | Windows/Microsoft x64 |
| --- | --- | --- |
| native `std::string` | `0x18` | `0x20` |
| `PlayerAuthenticationInfo` | `0x128` | `0x180` |
| XUID | `0x0` | `0x0` |
| Xbox Live name | `0x90` | `0xc0` |
| best display name | `0xd8` | `0x120` |
| authenticated UUID | `0x110` | `0x168` |
| optional engaged byte | `0x128` | `0x180` |

No RVA, signature, register contract, or layout is copied between platforms. Full evidence is in `OniBridge/generated/bds/1.26.44.3/<platform>/`.

## Patch and chain behavior

OniBridge validates the loaded executable hash, size, architecture, profile ID, expected call bytes, and decoded direct-call destination. It allocates a near read/write relay, writes an absolute jump to the replacement, changes the relay to read/execute, and replaces only the five-byte direct `CALL`. It refuses an existing/changed patch, double install, or unknown destination. Rollback restores bytes only when the site still contains OniBridge's own patch.

The replacement receives the same destination/source arguments as the original helper, consumes one locally verified staged identity, changes only the source XUID string, then calls the untouched original helper. Missing, expired, malformed, replayed, direct, or mismatched joins produce an empty optional. The backend UUID is not changed.

This patch runs inside the original BDS validation call already invoked by Endstone. Endstone's detour, ban logic, player initialization, plugin loader, and command hooks remain in their normal chain; OniBridge never calls an assumed pristine `_validateLoginPacket` and never detours a command function.

## Remaining evidence gates

The Windows synthetic executable hook harness passed under MSVC, including identity substitution, direct rejection, prior-call chaining, expected-byte checks, double-install rejection, rollback, and identity commit. A real Windows BDS 1.26.44.3/Endstone 0.11.9 offline-mode lifecycle then loaded and enabled the DLL, installed the exact hook, disabled it, and exited cleanly. The profile-specific Linux build and the same synthetic hook harness pass on GitHub's Ubuntu 22.04 runner. The Linux profile also completed its human review and operator-approved live acceptance matrix and is production-approved. Windows retains its separate human/live gates and remains a candidate.

BDS allowlist/operator checks internal to the proprietary function have not been instruction-by-instruction live-correlated. Endstone's post-validation player-ban check is proven to run after the selected call. Live acceptance must separately verify allowlist, `/op`, `/deop`, permissions, restart persistence, and every storage component.
