# Testing

Current completed results:

| Suite | Result | Evidence category |
| --- | --- | --- |
| `bdsctl` | 26 passed | unit test |
| `sdkgen` | 17 passed | unit test |
| packaging, egg, and ABI Python suite | 25 passed, 3 skipped | unit/platform fixture |
| OniLink Java 21 | 456 passed, 0 failed, 0 skipped | unit/integration fixture |
| Dashboard UI | 35 passed, 0 failed | component/integration fixture |
| OniBridge Windows CTest | 2 passed | native unit + synthetic executable hook harness |
| Windows BDS 1.26.44.3 + Endstone 0.11.9 | server start, DLL load/enable, exact hook install, disable/uninstall, exit 0 | partial live lifecycle; offline mode, no client |
| OniBridge Linux CTest | 2 passed on GitHub Ubuntu 22.04 | native unit + synthetic executable hook harness |
| Linux ABI policy | Maximum imported symbol version `GLIBC_2.35` | automated ELF `readelf` release gate |
| Linux release package | Native ELF64 x86-64 build, ABI report, and checksum-verified release bundle | build/package evidence |
| Linux BDS production acceptance | Exact hook and complete live acceptance matrix approved for BDS 1.26.44.3 + Endstone 0.11.9 | operator approval |

The acquisition suite covers official metadata, stable/preview separation, redirects, timeout/size/partial failures, malformed/HTML/unsafe ZIPs, required files, architectures, hashes, version mismatch, and EULA gating. SDK tests cover ELF/PE structure, signatures, boundaries, ABI separation, stale profiles, and layout gates. Token suites cover shared Java/C++ vectors, signature/context/time/key rotation, replay, IPv4/IPv6/mapped CIDRs, malformed inputs, compact client-data JWT extraction, and identity registry behavior.

The hook harness uses executable synthetic code rather than a mocked method name. It checks the direct call replacement, original destination, correct XUID substitution, rejection without a verified login, prior-call chaining, double-install prevention, safe rollback, and final identity registration.

The repository operator approved the complete Linux acceptance result for the exact BDS `1.26.44.3` + Endstone `0.11.9` target, including the human-review and live-test gates. The Linux profile is therefore production-approved. Windows has proven plugin load/hook lifecycle offline but no live client join and remains a candidate.

## Operations beta verification performed on 2026-08-21

The `0.3.0-beta.1` release gate ran the complete Java 21 suite from a clean temporary checkout:
456 tests passed with no failures or skips, the embedded dashboard production bundle compiled, the
standalone JAR was assembled, and both OniForge compatibility report formats were generated. The
dashboard also passed Prettier, ESLint, TypeScript, all 35 Vitest tests, and a separate production
build.

The new coverage exercises module dependency isolation, bounded typed events, tenant authorization,
SQLite migrations, OniFlow validation and execution, drain reservations, quarantine precedence,
dynamic backend revisions, canary expiry, role hierarchy cycles, support tickets, journey traces,
pack scanning limits, notification redaction, PWA assets, Protocol Lab fail-closed behavior, and the
documented native capability manifest. Native Linux and Windows compilation, CTest, and the Linux
GLIBC policy remain mandatory GitHub release-workflow gates because this Windows workstation does
not have the reviewed native toolchains installed.

## OniControl verification performed on 2026-08-20

The pre-change results and source commit are recorded in
[OniControl baseline](ONICONTROL_BASELINE.md). The final local run added 22 Java tests and kept all
existing gates green:

| Gate | Result |
| --- | --- |
| `./gradlew test standaloneJar --no-daemon` | 440 tests passed; standalone JAR built |
| Dashboard `format:check`, `lint`, `typecheck`, `test -- --run`, `build` | all passed; 32 tests in 4 files |
| Windows OniBridge plugin candidate build | core, control server, plugin adapter, unit test, and hook harness compiled |
| Windows CTest | 2/2 passed |
| Zig Windows native test executables | unit and hook harness compiled and exited 0 |
| Zig Linux plugin cross-build (`x86_64-linux-gnu.2.35`) | shared object linked successfully; upstream Endstone header warnings only |
| `bdsctl` | 26 passed |
| `sdkgen` | 17 passed |
| packaging/egg/ABI Python suite | 25 passed, 3 platform skips |

New fixtures cover strict JSON and recursively forbidden AI payload fields, a fixed shared Java/C++
HMAC request vector and framing, wrong secret, oversized/truncated frames, bounded nonce replay, disabled configuration,
separate forwarding/control secret sources, packet-rule tenant isolation in both directions,
factory dry encoding across every compiled codec, setup bundle secret separation, and virtual-menu
model limits. Support-bundle fixtures also verify that control actor/target/session data and private
addresses are removed while aggregate virtual-state counts remain available.

The local workstation cannot run a Linux BDS process or a real Bedrock client. Consequently the new
authoritative control actions, private entities, fake-block reapplication, and physical virtual-menu
interaction path remain at the capability statuses documented in [Compatibility](COMPATIBILITY.md),
not silently promoted by their compile/unit evidence. The established OniForward Linux acceptance
remains valid because that path was not bypassed or weakened.
