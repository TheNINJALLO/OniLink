# Complete installation guide

This guide installs stable OniLink `v0.2.0` in front of one native BDS + Endstone backend, then
shows how to add more BDS servers safely. For a shorter first pass, use [Quick start](QUICKSTART.md).

> [!IMPORTANT]
> The included Linux native profile is production-approved only for the exact BDS `1.26.44.3`
> executable and Endstone `0.11.9`. Keep `allow_unreviewed_profile=false`. A nearby BDS or Endstone
> version is a different binary target and must not reuse this plugin/profile pair.

## 1. Plan the deployment

OniLink needs one public UDP listener. Each BDS backend needs its own UDP listener that is private or
firewalled so only OniLink can reach it. The dashboard uses TCP and should remain loopback-only or sit
behind a protected HTTPS reverse proxy.

```text
Players
  |
  | UDP 19132 (public)
  v
OniLink 10.10.0.10
  |
  | UDP 19133 (private)
  v
Endstone + OniBridge + BDS 10.10.0.20
```

Example values used below:

| Purpose | Example | What to replace |
| --- | --- | --- |
| Public proxy address | `play.example.com:19132` | Your player-facing hostname and UDP port |
| OniLink private address | `10.10.0.10` | Source address the backend actually sees |
| Backend address | `10.10.0.20:19133` | Private BDS host and UDP port |
| Backend name | `survival` | Short unique route name |
| Bridge ID | `survival-main` | Unique validator instance ID |
| Key ID | `key-2026-01` | Non-secret identifier for the active key |
| Secret environment name | `ONIBRIDGE_SURVIVAL_SECRET` | Name only, never the secret itself |

The environment-name fields contain the name of a variable. For example,
`activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET` is correct; do not replace that text with the Base64
secret. The real Base64 value is assigned to that variable in the panel or service environment.

## 2. Download and verify the release

Download the stable release from GitHub:

```bash
mkdir -p onilink-release
gh release download v0.2.0 \
  --repo TheNINJALLO/OniLink \
  --dir onilink-release
cd onilink-release
sha256sum -c SHA256SUMS
```

At minimum, the Linux route uses:

```text
OniLink.jar
onilink.properties.example
onibridge-0.2.0-bds-1.26.44.3-linux-x86_64.so
onibridge.example.toml
onibridge-profile-1.26.44.3-linux-x86_64.json
SHA256SUMS
```

Do not use an artifact if checksum verification fails. The release never includes BDS itself; obtain
and operate BDS under its own terms.

## 3. Generate the forwarding secret

Generate one secret per backend:

```bash
openssl rand -base64 32
```

Copy the result directly into your secret manager, systemd environment file, or protected panel
variable. Never put it in `config.properties`, `onibridge.toml`, screenshots, logs, or source control.

For a Linux service, a root-owned environment file is one option:

```bash
sudo install -d -m 0750 /etc/onilink
sudoedit /etc/onilink/survival.env
sudo chmod 0600 /etc/onilink/survival.env
```

Its content is:

```dotenv
ONIBRIDGE_SURVIVAL_SECRET=REPLACE_WITH_THE_GENERATED_BASE64_VALUE
```

Use the same variable/value in both process environments. On Pterodactyl, let the panel inject a
protected variable. The dashboard **Add Backend** wizard can instead generate a key file with safe
permissions and package it into the backend setup ZIP; this avoids requiring a new egg variable.

## 4. Install OniLink

Create a dedicated directory and copy the runtime files:

```bash
sudo install -d -o onilink -g onilink -m 0750 /opt/onilink
sudo install -o onilink -g onilink -m 0644 OniLink.jar /opt/onilink/OniLink.jar
sudo install -o onilink -g onilink -m 0640 onilink.properties.example /opt/onilink/config.properties
```

Edit `/opt/onilink/config.properties`. A minimal, complete route is:

```properties
listener.host=0.0.0.0
listener.port=19132
publicAddress=play.example.com:19132

dashboard.enabled=true
dashboard.host=127.0.0.1
dashboard.port=8080
dashboard.dataDirectory=dashboard

backend.name=survival
backend.host=10.10.0.20
backend.port=19133
backends=survival
hubBackend=survival
backend.survival.host=10.10.0.20
backend.survival.port=19133
backend.protocol=auto

forwarding.proxyId=edge-1
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET
backend.survival.forwarding.tokenLifetimeMillis=5000
```

Java properties do not support trailing comments. This is wrong:

```properties
backend.port=19133 # private BDS port
```

Put comments on their own line. The complete option reference is
[`OniLink/onilink.example.properties`](../OniLink/onilink.example.properties).

Run OniLink manually for a first check:

```bash
cd /opt/onilink
set -a
. /etc/onilink/survival.env
set +a
java -jar OniLink.jar config.properties
```

On first start, the dashboard owner setup code is written to
`dashboard/FIRST_RUN_SETUP.txt`. Use it once, then protect the dashboard directory as credential
material.

## 5. Install Endstone and OniBridge

Install the exact supported BDS and Endstone versions. With BDS stopped, place both native files in
the Endstone plugin directory:

```bash
install -m 0644 \
  onibridge-0.2.0-bds-1.26.44.3-linux-x86_64.so \
  /srv/bds/plugins/onibridge-0.2.0-bds-1.26.44.3-linux-x86_64.so
install -m 0644 \
  onibridge-profile-1.26.44.3-linux-x86_64.json \
  /srv/bds/plugins/onibridge-profile-1.26.44.3-linux-x86_64.json
```

Start once if needed to create the OniBridge data directory, then stop BDS before editing. The
configuration is normally located at:

```text
<BDS root>/plugins/onibridge/onibridge.toml
```

Use this matching configuration:

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
allow_unreviewed_profile = false
allow_unknown_bds = false
allow_unknown_endstone = false

[legacy_verification]
enabled = false
```

If the release profile reports a different exact `profile_id`, use that value. Do not guess or copy
an ID from another BDS build.

## 6. Configure network isolation

Only OniLink should be public. For example, with UFW on the backend host:

```bash
sudo ufw allow proto udp from 10.10.0.10 to any port 19133
sudo ufw deny 19133/udp
```

Container networking may make BDS observe a bridge or NAT address instead of the physical OniLink
host. Use the actual source shown in backend logs, then express one address as `/32` for IPv4 or
`/128` for IPv6. Do not use `0.0.0.0/0` as a convenience value.

## 7. Start in the correct order

1. Start BDS/Endstone with the backend secret available.
2. Confirm OniBridge reports the exact profile and installs its hook without a critical error.
3. Start OniLink with the same secret available.
4. Confirm its UDP listener and dashboard bind successfully.
5. Join through the OniLink public address, never the backend address.

Useful checks:

```text
/onibridge status
/onilink status
```

The first join, leave, and rejoin must preserve the same XUID-backed BDS data. Test inventory,
location, permissions, backend commands, proxy commands, and direct-join rejection.

## 8. Add another BDS backend

The easiest path is **Dashboard → Add Backend**:

1. Enter a readable route name such as `creative`.
2. Enter the private IP/hostname of the destination BDS server.
3. Enter that BDS server's private UDP port.
4. Confirm the proxy source CIDR the new backend will see.
5. Let the wizard generate a unique secret and bridge ID.
6. Download the setup ZIP and install its OniBridge configuration/key files on the backend.
7. Restart the new backend, then restart or reload OniLink as instructed.

The wizard saves the corresponding proxy properties. A manual second route looks like:

```properties
backends=survival,creative

backend.creative.host=10.10.0.30
backend.creative.port=19134
backend.creative.protocol=auto
backend.creative.forwarding.enabled=true
backend.creative.forwarding.bridgeId=creative-main
backend.creative.forwarding.activeKeyId=key-2026-01
backend.creative.forwarding.activeSecretEnv=ONIBRIDGE_CREATIVE_SECRET
backend.creative.forwarding.tokenLifetimeMillis=5000
```

The matching backend uses `backend_name="creative"`, `bridge_id="creative-main"`, the same key ID,
and `active_secret_env="ONIBRIDGE_CREATIVE_SECRET"`. Give it a different secret from survival.

## 9. Enable the OniLink allowlist

BDS runs with its public online-mode check disabled behind the trusted proxy, so enforce admission at
OniLink. Add at least one authenticated XUID before enabling the list:

```text
allowlist add 2533274790000001 ExamplePlayer
allowlist list
```

Then configure and restart:

```properties
allowlist.enabled=true
allowlist.file=allowlist.properties
allowlist.kickMessage=You are not allow-listed on this server.
allowlist.disconnectOnRemoval=true
```

An empty enabled allowlist denies everyone. Administrator permissions do not bypass it.

## 10. Production checklist

- `SHA256SUMS` verified before installation.
- Exact BDS hash and Endstone version match the production profile.
- `allow_unreviewed_profile`, `allow_unknown_bds`, and `allow_unknown_endstone` are `false`.
- `shutdown_on_hook_failure` and `reject_direct_joins` are `true`.
- Every backend has a different secret and narrow trusted CIDR.
- Backend UDP ports are private/firewalled; only OniLink UDP is public.
- Dashboard is loopback-only or behind restricted HTTPS with TOTP enabled.
- Direct backend joins fail.
- Join, leave, rejoin, switching, failover, commands, packs, and player data are tested.
- Backups include proxy configuration, dashboard data, allowlist, native configuration, and key files.

## Upgrades

Read the target release notes, back up runtime data, verify new checksums, and compare the new
`onilink.properties.example` with your live configuration. OniLink application updates do not bypass
native profile approval: a BDS update requires a new exact OniBridge profile and possibly a new
native binary. Never carry a profile forward based only on a similar version number.

Pterodactyl installations on the `stable` channel check the latest non-prerelease on every reboot,
verify downloads, install valid changes atomically, and retain the previous runtime files for
rollback. See [Pterodactyl](PTERODACTYL.md).

## Removal

To remove the system, stop both processes, remove OniBridge and its configuration from the Endstone
plugin directory, restore the BDS authentication/network posture appropriate for direct operation,
and remove the OniLink listener only after preserving any dashboard and configuration backups you
need. Never expose an offline-mode backend directly.
