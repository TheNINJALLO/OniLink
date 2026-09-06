# Protocol packages and addon API 1

Protocol packages are trusted JVM code distributed independently of the proxy. They do not remove
the Endstone dependency or supply native hooks. The public entry point is
`dev.onistone.onilink.plugin.ProtocolPackage`, with `API_VERSION = 1` and
`void contribute(ProtocolRegistry.Builder registry)`.

Compile against `OniLink.jar` with Java 21. Do not shade the proxy or Cloudburst classes into a package.
The builder supports adding a `BedrockCodec`, explicitly replacing a codec, and registering a directed
`translation(fromProtocol, toProtocol, translator)`. A reverse route needs a separate translator.
Contributions must be stateless; live connections can retain older generations until they disconnect.

The JAR includes `onilink-protocol.json`:

```json
{
  "apiVersion": 1,
  "packageId": "my-protocol-fix",
  "version": "1.0.0",
  "provider": "example.protocol.ProtocolFix",
  "dependencies": {}
}
```

Dependencies map package IDs to exact versions. One generation classloader contains the selected
packages so dependencies can share helper classes. Cycles, missing versions, duplicate package IDs
and duplicate classes are rejected. Failed preparation keeps the active registry unchanged. Retired
classloaders are retained for active sessions, with a bounded generation count; a restart releases them.

Every package includes `fixtures.json`. Each fixture supplies sanitized source payload bytes **without
the packet header**, their source packet ID, and exact expected target bytes and target packet ID.
For example, a minimal join fixture is:

```json
{
  "sanitized": true,
  "fixtures": [{
    "id": "login-success-45",
    "category": "join",
    "clientVersion": "1.26.45",
    "backendVersion": "1.26.45",
    "direction": "clientbound",
    "packetId": 2,
    "input": "AAAAAA==",
    "expectedPacketId": 2,
    "expected": "AAAAAA=="
  }]
}
```

This single example deliberately has incomplete coverage. Supply passing fixtures for join, movement,
inventory, crafting, commands, packs and transfers. Every reachable route changed by the selected
packages needs all seven categories. A package also needs complete baseline category coverage even
when its contribution leaves the graph unchanged. Fixture suites cannot certify gameplay or native
compatibility. Use meaningful populated inventories, crafting recipes and command trees in releases;
synthetic empty packets only establish a baseline for the replay machinery.

`sourcePalette` and `targetPalette` may contain `items` with `id`, `name`, `componentBased`, and `blocks`
with `id`, `name`. Missing referenced runtime IDs fail replay instead of silently substituting air.
The current sanitized block fixture palette models empty state maps; captures whose semantic checks
depend on block states need a richer fixture before being used as acceptance evidence. Suites are
limited to 1,000 fixtures, 8 MiB of JSON and 1 MiB per packet. Candidate runs filter fixtures by the
candidate backend release. Never include live login tokens, private addresses or personal chat.

Generate publisher keys and signatures offline:

```powershell
java -cp OniLink.jar dev.onistone.onilink.modules.operations.ProtocolPackageCli keygen publisher private.pk8 trusted-publishers.properties
java -cp OniLink.jar dev.onistone.onilink.modules.operations.ProtocolPackageCli sign private.pk8 protocol-fix.jar signature.txt
```

Keep the PKCS#8 private key outside the server and source control. Configure
`protocols.trustedKeysFile=trusted-publishers.properties` on the proxy. Upload the JAR, verify its
publisher and detached signature in Update Center, then review and activate the selected package set.
The signed message is ASCII `OniLink-protocol-v1\n<lowercase archive SHA-256>\n`. Signing the whole
archive binds code, version, dependencies and fixture evidence to the same artifact.

Offline server inspection is also available without starting Endstone:

```powershell
java -cp OniLink.jar dev.onistone.onilink.modules.operations.ProtocolPackageCli inspect server windows bedrock-server-Windows-1.26.45.1.zip
```

## Addon SDK

Generate an ordinary addon project, including the Gradle wrapper and a runnable lifecycle contract:

```powershell
python tools/new_addon.py MyAddon --name MyAddon --package example.myaddon
$env:ONILINK_JAR = 'C:\path\to\OniLink.jar'
cd MyAddon
.\gradlew.bat check jar
```

Use JDK 21. The generated `src/contract/java` lifecycle check runs as part of `check`; ordinary
unit tests can be added separately under `src/test/java`. CI generates and builds this template
against the current standalone JAR.

An API 1 `onilink-plugin.properties` includes `name`, `main`, `api-version=1`, `version`, `permissions`
and optional comma-separated `depends=OtherAddon@1.0.0`. Supported capabilities are `events.subscribe`,
`protocol.contribute` and `listeners.bind`. The context provides scoped event subscriptions whose
registrations are closed when an addon unloads or fails to enable. Live authentication, joins, transfers,
disconnects and module events feed the bounded dispatcher. Existing descriptors keep their legacy
trusted capabilities so the installed bridge addon continues to work.

Permissions constrain the provided API; addons still execute with the JVM's privileges. Review and
trust their code. Failed enablement closes its classloader, invokes cleanup, and removes partially
registered protocol/listener contributions. Dependencies load first and disable in reverse order.
