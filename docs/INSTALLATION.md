# Complete installation and deployment guide

This is the full operator guide for installing OniLink and configuring either a native BDS + Endstone backend or a Geyser-backed Java server. If you only need the shortest path, use [Quick start](QUICKSTART.md). If you want ready-to-copy files, start in [`examples/`](../examples/README.md).

> [!IMPORTANT]
> `v0.1.0-candidate.1` is for controlled acceptance testing. The Linux artifact builds and passes its synthetic hook harness, but the exact profile remains `production_ready=false` until human review and live BDS join/storage testing are complete.

## Contents

1. [Choose a deployment path](#1-choose-a-deployment-path)
2. [Plan addresses and names](#2-plan-addresses-and-names)
3. [Download and verify the release](#3-download-and-verify-the-release)
4. [Create and install secrets](#4-create-and-install-secrets)
5. [Install a native BDS backend](#5-install-a-native-bds-backend)
6. [Configure and run OniLink](#6-configure-and-run-onilink)
7. [Install a Geyser backend](#7-install-a-geyser-backend)
8. [Configure multiple backends](#8-configure-multiple-backends)
9. [Run OniLink with systemd](#9-run-onilink-with-systemd)
10. [Deploy with Pterodactyl](#10-deploy-with-pterodactyl)
11. [Verify the installation](#11-verify-the-installation)
12. [Rotate keys](#12-rotate-keys)
13. [Roll back or uninstall](#13-roll-back-or-uninstall)

## 1. Choose a deployment path

| Target | Install on proxy | Install on backend | Do not install |
| --- | --- | --- | --- |
| BDS + Endstone | `OniLink.jar` | Profile-specific `onibridge.so` or `.dll` | `OniBridge-Geyser.jar` |
| Geyser + Java | `OniLink.jar` | `OniBridge-Geyser.jar` in Geyser | Native `onibridge.so`/`.dll` |

Both paths use the same `OniForward` trust model. OniLink authenticates the public Xbox client and signs a new short-lived claim for the selected backend. The backend validator accepts the claim only from the configured proxy source address.

## 2. Plan addresses and names

Do not start by copying configuration. First write down the values for your network.

### Example topology

```text
Players / Internet
       |
       | UDP 19132
       v
OniLink proxy
10.10.0.10
       |
       +---- UDP 19133 ----> Survival BDS + Endstone
       |                     10.10.0.20
       |
       +---- UDP 19134 ----> Private Geyser listener
                             10.10.0.30 -> Java server
```

Only OniLink's `19132/udp` listener is public. Backend ports are private or firewalled to the proxy.

### Configuration worksheet

| Value | Survival example | Java/Geyser example | Rule |
| --- | --- | --- | --- |
| Backend name | `survival` | `java` | Must match validator `backend_name` |
| Bridge ID | `survival-main` | `java-main` | Must match exactly on both sides |
| Active key ID | `key-2026-01` | `key-2026-01` | Identifier only; secret bytes must also match |
| Secret variable | `ONIBRIDGE_SURVIVAL_SECRET` | `ONIBRIDGE_JAVA_SECRET` | Use a different secret source per backend |
| Backend listener | `10.10.0.20:19133` | `10.10.0.30:19134` | Must not be public |
| Trusted proxy CIDR | `10.10.0.10/32` | `10.10.0.10/32` | Address the backend actually observes |
| Proxy ID | `edge-1` | `edge-1` | Stable unique ID for this OniLink instance |

Container NAT may change the source address observed by the backend. Do not assume it is the public IP or player IP. Check the backend-side address and configure the narrowest correct CIDR—normally one IPv4 `/32` or IPv6 `/128`.

## 3. Download and verify the release

### GitHub CLI

```bash
mkdir -p ~/onilink-download
gh release download v0.1.0-candidate.1 \
  --repo TheNINJALLO/OniLink \
  --dir ~/onilink-download
cd ~/onilink-download
sha256sum -c SHA256SUMS
```

Every listed file must report `OK`. Stop if any file is missing or mismatched.

### Browser download

Download every required file from the [candidate release](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.1.0-candidate.1), including `SHA256SUMS`. On Linux:

```bash
cd /path/to/downloads
sha256sum -c SHA256SUMS
```

On PowerShell:

```powershell
Set-Location C:\path\to\downloads
Get-Content SHA256SUMS | ForEach-Object {
    $expected, $name = $_ -split '  ', 2
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $name).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Checksum mismatch: $name" }
    Write-Host "OK  $name"
}
```

### Verify the native BDS target

The current Linux native plugin is only for:

| Requirement | Exact value |
| --- | --- |
| BDS | `1.26.44.3` |
| BDS executable SHA-256 | `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7` |
| Platform | Linux x86-64 / System V AMD64 |
| Endstone | `0.11.9` |
| Profile | `bds-1.26.44.3-linux-x86_64-06effdd00067f1ae` |

Verify the executable from the BDS directory:

```bash
sha256sum bedrock_server
```

Do not install the plugin if the hash differs. A nearby patch version, a Windows executable, or a different Endstone build is not interchangeable.

## 4. Create and install secrets

Every backend requires a different standard-Base64 secret containing at least 32 random bytes.

### Generate secrets

Linux/macOS:

```bash
openssl rand -base64 32
```

PowerShell:

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

Save the output in a password manager or secret manager. Do not paste it into Git, screenshots, logs, issues, or this documentation.

### Environment-variable method (recommended)

For the survival example, the same value must be present in both the OniLink process and the BDS/Endstone process:

```bash
export ONIBRIDGE_SURVIVAL_SECRET='REPLACE_WITH_THE_GENERATED_BASE64_VALUE'
```

For a second backend, generate a second secret:

```bash
export ONIBRIDGE_JAVA_SECRET='REPLACE_WITH_A_DIFFERENT_BASE64_VALUE'
```

An environment variable set in one terminal is not automatically available to another service, container, or user. Configure it separately for both processes.

### Restricted-file method

Use this only on a POSIX filesystem where ownership and permissions can be verified:

```bash
sudo install -d -m 0700 /etc/onilink/secrets
sudo sh -c 'printf "%s\n" "REPLACE_WITH_SECRET" > /etc/onilink/secrets/survival.key'
sudo chmod 0600 /etc/onilink/secrets/survival.key
```

Then configure exactly one source on each side:

```properties
backend.survival.forwarding.activeSecretEnv=
backend.survival.forwarding.activeSecretFile=/etc/onilink/secrets/survival.key
```

```toml
active_secret_env = ""
active_secret_file = "/etc/onilink/secrets/survival.key"
```

Do not configure both the environment and file fields. OniLink, native OniBridge, and OniBridge-Geyser reject ambiguous secret sources. On filesystems without verifiable POSIX permissions, use an environment variable.

## 5. Install a native BDS backend

### 5.1 Back up the backend

Stop BDS and back up at least:

- Worlds and player data
- Endstone `plugins/` and plugin-data directories
- Operators and allowlist
- Permission/rank databases
- Any plugin database indexed by XUID or UUID

Keep a copy of the last known working server directory so rollback is a file restore, not a repair exercise.

### 5.2 Install the native plugin

From the BDS root:

```bash
cp /path/to/onibridge-0.1.0-bds-1.26.44.3-linux-x86_64.so plugins/
chmod 0644 plugins/onibridge-0.1.0-bds-1.26.44.3-linux-x86_64.so
```

Start the server once using your normal Endstone launch command. OniBridge creates:

```text
plugins/onibridge/onibridge.toml
```

The first start intentionally shuts down because the generated bridge name, backend name, and profile are not ready. That shutdown is expected.

### 5.3 Configure `onibridge.toml`

For the example network, replace the generated file with:

```toml
bridge_id = "survival-main"
backend_name = "survival"
trusted_proxy_cidrs = ["10.10.0.10/32"]
shutdown_on_hook_failure = true
reject_direct_joins = true

[forwarding]
protocol = 2
active_key_id = "key-2026-01"
active_secret_env = "ONIBRIDGE_SURVIVAL_SECRET"
active_secret_file = ""
previous_key_id = ""
previous_secret_env = ""
previous_secret_file = ""
maximum_token_size = 4096
maximum_lifetime_ms = 10000
allowed_clock_skew_ms = 2000
replay_cache_max_entries = 10000

[identity]
uuid_mode = "preserve_backend"
verify_post_login_xuid = true
store_verified_identities = true

[commands]
register_native_commands = true
command_namespace = "onibridge"
interfere_with_backend_commands = false

[compatibility]
required_profile = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"
allow_unreviewed_profile = true
allow_unknown_bds = false
allow_unknown_endstone = false

[legacy_verification]
enabled = false
```

Copyable version: [`examples/single-bds/onibridge.toml`](../examples/single-bds/onibridge.toml).

Important rules:

- `backend_name` must equal the OniLink backend list name.
- `bridge_id` and `active_key_id` must match OniLink exactly.
- `trusted_proxy_cidrs` contains proxy source addresses, never player addresses.
- `reject_direct_joins` must stay `true`.
- `identity.uuid_mode` should stay `preserve_backend`.
- `interfere_with_backend_commands`, `allow_unknown_bds`, `allow_unknown_endstone`, and legacy verification must stay `false`.
- `allow_unreviewed_profile=true` is only the explicit opt-in for this candidate test. It is not a production approval.

Native TOML is strict. Unknown keys, duplicate keys, invalid types, and unsupported values stop startup.

### 5.4 Make the backend private

Example UFW rules on the BDS host:

```bash
sudo ufw allow from 10.10.0.10 to any port 19133 proto udp
sudo ufw deny 19133/udp
sudo ufw status numbered
```

Adapt these rules to your firewall and management access. Confirm the specific allow rule appears before the broad deny rule. If proxy and backend share a host, bind BDS to loopback or a private interface when supported.

### 5.5 Start and inspect OniBridge

Start the BDS/Endstone process with `ONIBRIDGE_SURVIVAL_SECRET` in its environment. The console must report that the exact native identity hook is active.

Stop immediately if you see:

- Missing secret or invalid Base64
- Wrong BDS hash or executable size
- Wrong Endstone version
- Missing or wrong profile ID
- Expected-byte/call-target mismatch
- Hook chain uncertainty
- Automatic shutdown after a critical OniBridge message

Do not enable compatibility bypasses to make the server remain online.

## 6. Configure and run OniLink

### 6.1 Prepare the proxy directory

```bash
sudo useradd --system --home /opt/onilink --shell /usr/sbin/nologin onilink 2>/dev/null || true
sudo install -d -m 0750 -o onilink -g onilink /opt/onilink
sudo install -m 0644 -o onilink -g onilink /path/to/OniLink.jar /opt/onilink/OniLink.jar
sudo install -m 0640 -o onilink -g onilink /path/to/onilink.properties.example /opt/onilink/config.properties
```

For a personal test environment, a normal user-owned directory is also acceptable. Keep the jar, configuration, cache, logs, and resource-pack directories together.

### 6.2 Configure a single BDS backend

Edit `/opt/onilink/config.properties`:

```properties
# Public player listener.
listener.host=0.0.0.0
listener.port=19132
publicAddress=play.example.com:19132

# Default and only backend.
backend.name=survival
backend.host=10.10.0.20
backend.port=19133
backends=survival
hubBackend=survival
backend.protocol=auto

# Explicit named backend address.
backend.survival.host=10.10.0.20
backend.survival.port=19133

# Must match onibridge.toml.
forwarding.proxyId=edge-1
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET
backend.survival.forwarding.tokenLifetimeMillis=5000

# One-backend behavior.
join.try=survival
join.attemptsPerBackend=2
failover.enabled=false
protocolFault.action=disconnect
protocolFault.logFile=logs/protocol-errors.log

# Start with no proxy administrators. Add trusted XUIDs later.
permissions.admins=
permissions.adminCommands=alert,glist,perm,send

# Public listener protection.
security.rateLimit.enabled=true
security.rateLimit.packetLimit=500
security.rateLimit.globalPacketLimit=100000
security.sendConnectionCookie=true
security.maxConnectionsPerAddress=5
security.maxConnectionAttempts=8
security.connectionAttemptWindowMillis=10000
security.requireXuid=true
security.commandCooldownMillis=1000

motd=OniLink Network
subMotd=Survival
gameType=Survival
maxPlayers=20

resourcePacks.dir=
resourcePacks.cacheBackendPacks=true
crossBackendPalette=true
compression=zlib
compressionThreshold=0
```

Copyable version: [`examples/single-bds/onilink.properties`](../examples/single-bds/onilink.properties).

Java properties syntax matters:

- Put comments on their own lines.
- Do not write `listener.port=19132 # public`; the comment becomes part of the value.
- Backend names are comma-separated in `backends` and must have matching `backend.<name>.*` blocks.
- Prefer `backend.protocol=auto` unless you are deliberately pinning a tested protocol.

### 6.3 Open the public listener

Example UFW rule on the proxy host:

```bash
sudo ufw allow 19132/udp
sudo ufw status numbered
```

Forward UDP `19132` from your router/provider only to the OniLink host. Do not forward backend ports.

### 6.4 Start manually for the first test

Start the backend first. On the OniLink host:

```bash
cd /opt/onilink
export ONIBRIDGE_SURVIVAL_SECRET='REPLACE_WITH_THE_GENERATED_BASE64_VALUE'
java -jar OniLink.jar config.properties
```

Keep this terminal private because shell history and process-management mistakes can expose secrets. Move to systemd, a panel secret, or a protected environment file after the first controlled test.

## 7. Install a Geyser backend

Use this path instead of native OniBridge for a Geyser-backed Java server.

### 7.1 Install the extension

```bash
cp /path/to/OniBridge-Geyser.jar /path/to/geyser/extensions/
```

Start Geyser once, then stop it. The extension creates:

```text
extensions/onibridge-geyser/config.properties
```

### 7.2 Configure OniBridge-Geyser

```properties
bridge_id=java-main
backend_name=java
trusted_proxy_cidrs=10.10.0.10/32

active_key_id=key-2026-01
active_secret_env=ONIBRIDGE_JAVA_SECRET
active_secret_file=

previous_key_id=
previous_secret_env=
previous_secret_file=

maximum_token_size=4096
maximum_lifetime_millis=10000
allowed_clock_skew_millis=2000
replay_cache_maximum_entries=10000
```

Copyable version: [`examples/mixed-bds-geyser/onibridge-geyser.properties`](../examples/mixed-bds-geyser/onibridge-geyser.properties).

OniBridge-Geyser also rejects unknown keys and requires exactly one active secret source.

### 7.3 Configure Geyser's private listener

Merge these settings into the matching sections of Geyser's `config.yml`:

```yaml
bedrock:
  address: 10.10.0.30
  port: 19134

java:
  auth-type: floodgate

advanced:
  bedrock:
    validate-bedrock-login: false
    use-waterdogpe-forwarding: false
```

`validate-bedrock-login: false` is safe only because OniLink performs public authentication and the private listener is guarded by OniBridge-Geyser. Never expose this listener directly.

Firewall example on the Geyser host:

```bash
sudo ufw allow from 10.10.0.10 to any port 19134 proto udp
sudo ufw deny 19134/udp
```

### 7.4 Add the Geyser backend to OniLink

```properties
backends=survival,java

backend.java.host=10.10.0.30
backend.java.port=19134
backend.java.dropSubChunkRequests=true
backend.java.forwarding.enabled=true
backend.java.forwarding.bridgeId=java-main
backend.java.forwarding.activeKeyId=key-2026-01
backend.java.forwarding.activeSecretEnv=ONIBRIDGE_JAVA_SECRET
backend.java.forwarding.tokenLifetimeMillis=5000
```

`backend.java.dropSubChunkRequests=true` is required for a Geyser backend after switching from BDS semantics.

Start Geyser with `ONIBRIDGE_JAVA_SECRET` present in its environment. A missing or invalid extension configuration rejects every join instead of allowing an unverified connection.

The focused guide is [Geyser integration](GEYSER.md).

## 8. Configure multiple backends

The complete mixed example is [`examples/mixed-bds-geyser/`](../examples/mixed-bds-geyser/README.md).

Key rules:

1. List every backend once in `backends`.
2. Configure `backend.<name>.host` and `.port` for every named backend.
3. Use a unique bridge ID and a unique secret source per backend.
4. The OniLink backend name must match that validator's `backend_name`.
5. Set per-backend failover order deliberately.
6. Set `dropSubChunkRequests=true` only on Geyser-like backends.

Example failover:

```properties
join.try=survival,java
join.attemptsPerBackend=2
failover.enabled=true
failover.fallbacks=survival,java
backend.survival.fallback=java
backend.java.fallback=survival
failover.onBackendKick=auto
```

Do not point a backend fallback at itself as the only choice; that creates retries without an alternate destination.

## 9. Run OniLink with systemd

### 9.1 Create a protected environment file

```bash
sudo sh -c 'printf "%s\n" "ONIBRIDGE_SURVIVAL_SECRET=REPLACE_WITH_SECRET" > /opt/onilink/onilink.env'
sudo chown onilink:onilink /opt/onilink/onilink.env
sudo chmod 0600 /opt/onilink/onilink.env
```

For multiple backends, add one line per unique environment variable.

### 9.2 Create the service

Create `/etc/systemd/system/onilink.service`:

```ini
[Unit]
Description=OniLink Bedrock proxy
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=onilink
Group=onilink
WorkingDirectory=/opt/onilink
EnvironmentFile=/opt/onilink/onilink.env
ExecStart=/usr/bin/java -jar /opt/onilink/OniLink.jar /opt/onilink/config.properties
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/onilink

[Install]
WantedBy=multi-user.target
```

Enable it only after the backend starts cleanly:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now onilink
sudo systemctl status onilink --no-pager
sudo journalctl -u onilink -f
```

### 9.3 Add the secret to an existing Endstone service

If your backend service is named `endstone.service`, create a drop-in:

```bash
sudo systemctl edit endstone
```

```ini
[Service]
EnvironmentFile=/etc/onilink/survival.env
```

Create `/etc/onilink/survival.env` with mode `0600`, reload systemd, and restart the backend. Replace the service name and path with your actual launcher. The environment must be attached to the process that loads the native plugin.

## 10. Deploy with Pterodactyl

Use separate Pterodactyl servers for OniLink and each backend.

Import the released [`egg-onilink.json`](../packaging/pterodactyl/egg-onilink.json) through **Admin Panel → Nests → Import Egg**, create an OniLink server using its Java 21 image, and give it one public UDP allocation. The egg verifies its bootstrap JAR, updater, and template against `SHA256SUMS`, preserves an existing configuration on reinstall, and maps the primary allocation to `listener.port`. On every container start, it checks the newest published OniLink release and atomically installs the JAR only after checksum validation; a failed check falls back to the existing JAR.

Set the egg's backend host/port to the private allocation reachable through Wings. Before first start, an administrator must fill the non-user-viewable `ONIBRIDGE_FORWARDING_SECRET` variable with standard Base64 for at least 32 random bytes and set the same value on the backend validator.

### Allocations

| Pterodactyl server | Allocation | Exposure |
| --- | --- | --- |
| OniLink | `19132/udp` | Public |
| Survival BDS | `19133/udp` | Private/firewalled to proxy |
| Geyser | `19134/udp` | Private/firewalled to proxy |

### Variables

Add the same administrator-only secret variable to the two containers that need it:

| Container | Variable |
| --- | --- |
| OniLink | `ONIBRIDGE_SURVIVAL_SECRET` |
| Survival BDS | `ONIBRIDGE_SURVIVAL_SECRET` |
| OniLink | `ONIBRIDGE_JAVA_SECRET` |
| Geyser | `ONIBRIDGE_JAVA_SECRET` |

Use different values for survival and Java. Do not put `MINECRAFT_EULA_ACCEPTED` into OniLink; that variable belongs only to controlled BDS acquisition/profile tooling and is unrelated to runtime forwarding.

### Container networking

The address seen by a backend may be the node, Docker bridge, Wings network, or proxy container address. Determine the actual source before setting `trusted_proxy_cidrs`. Prefer stable private networking and a narrow address. Do not trust the entire hosting-provider subnet unless there is no safer architecture.

Startup order:

1. BDS/Endstone or Geyser backend
2. Confirm validator active
3. OniLink
4. Test client

See [Pterodactyl](PTERODACTYL.md) for the security summary.

## 11. Verify the installation

### Process checks

- OniLink is listening on the intended public UDP address and port.
- Backend listeners are reachable from OniLink and unreachable from an untrusted host.
- Every backend validator reports successful configuration.
- Native OniBridge reports the exact hook active.
- No process reports a missing secret, wrong key ID, clock problem, or profile mismatch.

### Join checks

Run these in a controlled test environment and record the result:

1. Valid join through OniLink.
2. Direct backend join blocked by firewall and rejected by validator.
3. Wrong secret, bridge ID, backend name, and key ID rejected.
4. Expired, future, tampered, and replayed claims rejected.
5. Disconnect/reconnect preserves inventory and Ender Chest on BDS.
6. Restart/rejoin preserves identity and storage.
7. Two players can connect concurrently without identity crossover.
8. Bans, allowlist, operators, permissions, commands, and real client address behave correctly.
9. BDS-to-Geyser and Geyser-to-BDS switches work when configured.

Use the full [Testing](TESTING.md) matrix. Do not change the profile status based on a single successful join.

### Fast diagnosis

| Symptom | First checks |
| --- | --- |
| OniBridge shuts BDS down | First critical log, secret, exact hash, Endstone, profile ID, expected bytes |
| Every join rejected | Backend/bridge/key IDs, secret bytes, clocks, proxy source CIDR |
| Direct join succeeds | Firewall, listener binding, validator loaded, `reject_direct_joins` |
| Rejoin has empty inventory | Stop; native XUID was not active before storage selection |
| Geyser kicks for sub-chunk packet | `backend.java.dropSubChunkRequests=true` |
| One backend works, second does not | Unique secret source and correct per-backend name/bridge block |

Continue with [Troubleshooting](TROUBLESHOOTING.md).

## 12. Rotate keys

The validators support one active and one previous key.

Example transition from `key-2026-01` to `key-2026-02`:

1. Generate a new secret.
2. On the validator, move the old key ID/source into the previous fields and put the new key into the active fields.
3. On OniLink, set the new active fields. Optionally retain previous fields for configuration symmetry; OniLink signs only with the active key.
4. Restart the validator first, then OniLink.
5. Test a join.
6. Wait longer than maximum lifetime plus clock skew—normally more than 12 seconds.
7. Remove the previous key from both configurations and restart/reload again.

Native validator example during rotation:

```toml
active_key_id = "key-2026-02"
active_secret_env = "ONIBRIDGE_SURVIVAL_SECRET_NEW"
previous_key_id = "key-2026-01"
previous_secret_env = "ONIBRIDGE_SURVIVAL_SECRET_OLD"
```

OniLink active signer:

```properties
backend.survival.forwarding.activeKeyId=key-2026-02
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET_NEW
backend.survival.forwarding.previousKeyId=key-2026-01
backend.survival.forwarding.previousSecretEnv=ONIBRIDGE_SURVIVAL_SECRET_OLD
```

Never reuse one key ID for different bytes.

## 13. Roll back or uninstall

### Native BDS

1. Stop OniLink and BDS.
2. Remove the OniBridge `.so`/`.dll` from `plugins/`.
3. Preserve or archive `plugins/onibridge/` with your test evidence.
4. Restore the backed-up server/plugin data if the test changed it.
5. Restore the previous network exposure only after confirming no backend now trusts forged/offline joins.

### Geyser

1. Stop OniLink and Geyser.
2. Remove `OniBridge-Geyser.jar`.
3. Restore Geyser's normal public-login validation before exposing its Bedrock listener.
4. Remove the OniLink backend block or forwarding path.

### Proxy

Stop and disable `onilink.service`, archive its configuration/logs, remove public UDP forwarding, and delete secrets from the service/panel environment.

Do not leave a private-listener authentication bypass exposed after removing its validator.
