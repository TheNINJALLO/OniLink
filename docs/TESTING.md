# Testing

Current completed results:

| Suite | Result | Evidence category |
| --- | --- | --- |
| `bdsctl` | 26 passed | unit test |
| `sdkgen` | 17 passed | unit test |
| recursive/package assembly scanners | 5 passed | unit test |
| OniLink Java 21 | 397 passed, 0 failed, 0 skipped | unit/integration fixture |
| OniBridge-Geyser Java 21 | 18 passed, 0 failed, 0 skipped | protocol/security/compatibility unit test |
| OniBridge Windows CTest | 2 passed | native unit + synthetic executable hook harness |
| Windows BDS 1.26.44.3 + Endstone 0.11.9 | server start, DLL load/enable, exact hook install, disable/uninstall, exit 0 | partial live lifecycle; offline mode, no client |
| OniBridge Linux CTest | 2 passed on GitHub Ubuntu 22.04 | native unit + synthetic executable hook harness |
| Linux ABI policy | Maximum imported symbol version `GLIBC_2.35` | automated ELF `readelf` release gate |
| Linux release package | Native ELF64 x86-64 build, ABI report, and checksum-verified release bundle | build/package evidence |
| Linux BDS operator smoke test | Exact hook active and valid proxied player join reported on BDS 1.26.44.3 + Endstone 0.11.9 | operator report; complete live matrix not recorded |

The acquisition suite covers official metadata, stable/preview separation, redirects, timeout/size/partial failures, malformed/HTML/unsafe ZIPs, required files, architectures, hashes, version mismatch, and EULA gating. SDK tests cover ELF/PE structure, signatures, boundaries, ABI separation, stale profiles, and layout gates. Token suites cover shared Java/C++/Geyser vectors, signature/context/time/key rotation, replay, IPv4/IPv6/mapped CIDRs, malformed inputs, compact client-data JWT extraction, strict Geyser configuration, address application, and identity registry behavior.

The hook harness uses executable synthetic code rather than a mocked method name. It checks the direct call replacement, original destination, correct XUID substitution, rejection without a verified login, prior-call chaining, double-install prevention, safe rollback, and final identity registration.

A valid Linux proxied player join with the exact hook active has been operator-reported, but the complete acceptance record has not been supplied. Required acceptance still includes direct/unsigned/tampered/expired/replayed/untrusted joins; two-player concurrency; restart/rejoin inventory and Ender Chest; all remaining storage fields; bans/allowlist/operators/permissions; real IP; Python and C++ Endstone command fixtures; aliases/overloads/enums/soft enums/tab completion/output; switching; protocol translation; and clean shutdown/unload. Windows has proven plugin load/hook lifecycle offline but no live client join. OniBridge-Geyser additionally needs a live Geyser 2.11/Floodgate join, direct-listener rejection, real-IP observation on Java, reconnect, and backend-switch testing.
