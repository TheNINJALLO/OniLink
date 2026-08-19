# Native BDS Setup

This walkthrough installs the Linux native bridge for one BDS backend. The complete operator manual, including backups, systemd, Pterodactyl, rotation, rollback, and multi-backend examples, is in the [installation guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/INSTALLATION.md).

> [!WARNING]
> `v0.1.4` is production-approved only for BDS `1.26.44.3` Linux x86-64 with Endstone `0.11.9`; a nearby version is not compatible. The exact profile reports `production_ready=true` and every mismatch still fails closed.

## Example network

| Item | Example value |
| --- | --- |
| Public OniLink listener | `10.10.0.10:19132/udp` |
| Private BDS listener | `10.10.0.20:19133/udp` |
| Backend name | `survival` |
| Bridge ID | `survival-main` |
| Active key ID | `key-2026-01` |
| Secret variable | `ONIBRIDGE_SURVIVAL_SECRET` |

Replace the addresses and public hostname in the example files. The backend port must be private or firewalled so only OniLink can reach it.

## 1. Verify and back up

Stop the server and back up the world, `server.properties`, allowlist, permissions, operators, Endstone configuration, and the entire `plugins/` directory.

Verify the exact BDS executable before installing:

```bash
cd /srv/bds
sha256sum bedrock_server
```

The SHA-256 must be:

```text
06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7
```

## 2. Install the plugin

Copy the release artifact into Endstone's plugin directory:

```bash
install -m 0755 \
  onibridge-0.1.4-bds-1.26.44.3-linux-x86_64.so \
  /srv/bds/plugins/onibridge-0.1.4-bds-1.26.44.3-linux-x86_64.so
```

Start BDS once. OniBridge creates `plugins/onibridge/onibridge.toml` and intentionally stops because no forwarding secret has been configured yet. This first shutdown is expected.

## 3. Create the secret

Generate a standard Base64 value containing at least 32 random bytes:

```bash
openssl rand -base64 32
```

Store it in a service manager or secret store, not in Git. For an interactive test session:

```bash
export ONIBRIDGE_SURVIVAL_SECRET='REPLACE_WITH_THE_GENERATED_VALUE'
```

The same variable name and value must be available to the OniLink process and this BDS process. Use a unique secret for every backend.

## 4. Configure OniBridge

Edit `/srv/bds/plugins/onibridge/onibridge.toml`:

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

`trusted_proxy_cidrs` must contain the source address BDS actually observes. With Docker or panel networking this may be a bridge/NAT address rather than the public proxy address. Use the narrowest correct range, normally an IPv4 `/32` or IPv6 `/128`.

The parser rejects unknown or duplicate options. Configure exactly one active secret source: `active_secret_env` or `active_secret_file`, never both. A secret file must be accessible only to the BDS service account.

## 5. Configure OniLink

Use the complete [single-BDS proxy example](https://github.com/TheNINJALLO/OniLink/blob/main/examples/single-bds/onilink.properties). These values must match the TOML exactly:

```properties
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET
backend.survival.forwarding.tokenLifetimeMillis=5000
```

Java properties do not support trailing inline comments. Put comments on their own lines, or the comment text becomes part of the setting value.

## 6. Restrict and start

Allow backend UDP only from the proxy, adapting the addresses and interface to your host:

```bash
sudo ufw allow proto udp from 10.10.0.10 to 10.10.0.20 port 19133
```

Start BDS first with the secret present, then start OniLink:

```bash
java -jar OniLink.jar config.properties
```

The BDS log must report the expected exact profile and active forwarding validator. Treat a critical hook message or automatic shutdown as a failed compatibility/security check; do not enable an unknown-version bypass.

## 7. Verify

Test all of the following before allowing users onto the native-profile deployment:

- A valid join through OniLink succeeds.
- A direct connection to the backend is rejected.
- Tampered, expired, and replayed claims are rejected.
- The backend sees the real player address and expected XUID.
- Inventory, Ender Chest, permissions, operator status, bans, and allowlist survive reconnects and restarts.
- Two or more players can join concurrently without identity crossover.

Use the full [acceptance checklist](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TESTING.md) and [troubleshooting guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TROUBLESHOOTING.md).
