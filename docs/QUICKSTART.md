# Quick start: Linux release

This guide brings up stable OniLink `v0.2.0` in front of one BDS `1.26.44.3` + Endstone `0.11.9` backend. The included exact Linux native profile is production-approved and fails closed on every mismatched runtime detail.

For service setup, firewalls, secret files, full configuration references, Pterodactyl, key rotation, and rollback, use the [complete installation guide](INSTALLATION.md). Ready-to-copy configurations are in the [single-BDS example](../examples/single-bds/README.md).

## 1. Prepare the hosts

You need:

- A Linux x86-64 backend running your own licensed BDS `1.26.44.3` installation.
- Endstone `0.11.9` on that backend.
- Java 21 for OniLink.
- A private or firewalled UDP path from OniLink to BDS.
- The [current stable release](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.2.0).

Do not expose the backend's Bedrock listener publicly. Players connect to OniLink; only OniLink connects to the backend.

## 2. Download and verify the release

```bash
gh release download v0.2.0 \
  --repo TheNINJALLO/OniLink \
  --dir onilink-release
cd onilink-release
sha256sum -c SHA256SUMS
```

Every listed file must report `OK`. Stop if a checksum differs.

PowerShell verification:

```powershell
Set-Location onilink-release
Get-Content SHA256SUMS | ForEach-Object {
    $expected, $name = $_ -split '  ', 2
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $name).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Checksum mismatch: $name" }
}
```

## 3. Create the forwarding secret

Create a different secret for every backend:

```bash
openssl rand -base64 32
```

Store the result in your secret manager or panel environment as `ONIBRIDGE_FORWARDING_SECRET`. The same environment variable and value must be available to OniLink and this backend. Never commit the value.

## 4. Install OniBridge

1. Stop BDS.
2. Copy `onibridge-0.2.0-bds-1.26.44.3-linux-x86_64.so` into the Endstone `plugins/` directory.
3. Start BDS once. OniBridge creates `plugins/onibridge/onibridge.toml` and shuts the server down because it is not configured yet.
4. Edit the generated file:

```toml
bridge_id = "survival-main"
backend_name = "survival"
trusted_proxy_cidrs = ["10.0.0.10/32"]
shutdown_on_hook_failure = true
reject_direct_joins = true

[forwarding]
protocol = 2
active_key_id = "key-1"
active_secret_env = "ONIBRIDGE_FORWARDING_SECRET"

[compatibility]
required_profile = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"
allow_unreviewed_profile = false
allow_unknown_bds = false
allow_unknown_endstone = false
```

Replace `10.0.0.10/32` with the exact OniLink source address observed by the backend. Keep `allow_unreviewed_profile=false`; the exact released profile is production-approved.

5. Start BDS with `ONIBRIDGE_FORWARDING_SECRET` present in its environment.
6. Confirm the console reports an active exact profile. A shutdown or critical hook message is a failed test—do not bypass it.

Keep `onibridge-profile-1.26.44.3-linux-x86_64.json` and `linux-compatibility-manifest.json` with your test evidence; they document the exact profile but are not a substitute for the embedded runtime checks.

## 5. Configure OniLink

Create a working directory and copy in `OniLink.jar` plus `onilink.properties.example`:

```bash
mkdir -p onilink-runtime
cp OniLink.jar onilink-runtime/
cp onilink.properties.example onilink-runtime/config.properties
```

Set the backend and forwarding values in `config.properties`:

```properties
listener.host=0.0.0.0
listener.port=19132

backend.name=survival
backends=survival
hubBackend=survival
backend.survival.host=10.0.0.20
backend.survival.port=19133

forwarding.proxyId=edge-1
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-1
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_FORWARDING_SECRET
backend.survival.forwarding.tokenLifetimeMillis=5000
```

The backend name, bridge ID, key ID, and secret must match OniBridge exactly.

## 6. Start and test

Start the backend first, then OniLink:

```bash
java -jar OniLink.jar config.properties
```

On first start, OniLink also prints its loopback dashboard URL and creates `dashboard/FIRST_RUN_SETUP.txt`. Copy its one-time code, open `http://127.0.0.1:8080/` on the proxy host, and create the owner account. For remote administration, follow the [dashboard HTTPS guide](DASHBOARD.md) before exposing it.

Connect a test client to OniLink and record the results for:

1. Valid proxied join.
2. Direct backend join rejection.
3. Tampered, expired, and replayed claim rejection.
4. Disconnect/reconnect with inventory and Ender Chest continuity.
5. Two simultaneous players.
6. Restart and rejoin.
7. Operator, allowlist, ban, permission, command, and real-address behavior.

Use the full [testing checklist](TESTING.md). Do not change the profile to production-ready until every required gate is independently reviewed and recorded.

## Next steps

- Add more BDS backends with the dashboard wizard and the [step-by-step backend guide](ADDING_BACKEND.md).
- For panel deployment, continue with [Pterodactyl](PTERODACTYL.md).
- To configure accounts, roles, TOTP, and the web control plane, continue with [Dashboard](DASHBOARD.md).
- If startup or joins fail, use [Troubleshooting](TROUBLESHOOTING.md).
