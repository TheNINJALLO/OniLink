# OniLink Pterodactyl egg

`egg-onilink.json` is the importable PTDL v2 egg for the OniLink proxy process. It does not redistribute BDS and it does not replace the separate BDS/Endstone or Geyser backend server.

## What the egg does

- Runs OniLink on a Java 21 Pterodactyl image.
- Downloads an exact public OniLink bootstrap release during installation or reinstall.
- Downloads `SHA256SUMS` and verifies `OniLink.jar`, `start-onilink.sh`, and the configuration template before installation succeeds.
- Checks the newest published GitHub release on every container start, including prereleases.
- Replaces `OniLink.jar` atomically only after checksum validation and keeps the previous JAR for rollback.
- Starts the installed JAR when GitHub is temporarily unavailable or an update cannot be verified.
- Creates `config.properties` only when it is missing, preserving operator changes during reinstall.
- Maps the primary Pterodactyl allocation to Bedrock UDP and dashboard TCP on the same numeric port.
- Persists dashboard accounts/audit data and provides an operator switch to disable the web listener.
- Maps the backend host, backend port, MOTD, and displayed player limit into the default configuration.
- Declares forwarding secrets as administrator-only environment variables with no default value.

## Import

1. Download `egg-onilink.json` from the matching [GitHub release](https://github.com/TheNINJALLO/OniLink/releases) or this directory.
2. In Pterodactyl, open **Admin Panel → Nests**.
3. Select or create an appropriate nest, choose **Import Egg**, and upload the JSON file.
4. Create an OniLink server from the imported egg.
5. Assign one allocation; its primary port becomes both `listener.port` (UDP) and `dashboard.port` (TCP).
6. Set the private backend host and UDP port.
7. Generate `openssl rand -base64 32` and set `ONIBRIDGE_FORWARDING_SECRET` in the administrator-facing server variables. Set the identical value on the matching backend validator.
8. Start the backend first, confirm its validator is active, and then start OniLink.
9. Open `dashboard/FIRST_RUN_SETUP.txt` in the file manager and use its one-time code to create the dashboard owner.

The initial `127.0.0.1` backend host works only when a backend is deliberately in the same container, which is not the recommended layout. Change it to the private hostname or address reachable through Wings networking.

## Multiple backends

The egg exposes optional `ONIBRIDGE_SURVIVAL_SECRET` and `ONIBRIDGE_JAVA_SECRET` variables for the repository's mixed example. Edit `config.properties` using the [mixed BDS/Geyser example](../../examples/mixed-bds-geyser/onilink.properties), then set both admin-only variables to different secret values.

Pterodactyl rewrites only these fields at startup:

- `listener.host`
- `listener.port`
- `dashboard.enabled`, `dashboard.host`, and `dashboard.port`
- `backend.host` and `backend.port`
- `backend.default.host` and `backend.default.port`
- `motd`
- `maxPlayers`

All other fields remain under direct operator control in `config.properties`.

The dashboard is reachable at `http://NODE-OR-DOMAIN:PRIMARY_PORT/` when **Enable operations dashboard** is true and TCP is published. Use a trusted network for first-run setup, then place it behind restricted HTTPS. Bedrock and the dashboard share a number without conflict because Bedrock binds UDP and the dashboard binds TCP. Persist `dashboard/` and protect it as credential material. The [dashboard guide](../../docs/DASHBOARD.md) covers roles, TOTP, reverse proxies, backups, and recovery.

## Automatic updates and rollback

Restart the container to check for an update. `start-onilink.sh` queries the public release list, selects the newest published release, downloads its `OniLink.jar` and `SHA256SUMS`, and installs the JAR only when its SHA-256 value matches. The process includes prereleases because OniLink is currently distributed as a candidate.

`ONILINK_VERSION` is only the bootstrap version used during server creation or **Reinstall Server**. It does not pin later starts. Reinstall preserves `config.properties`; compare it with `onilink.properties.example` after a bootstrap upgrade.

After a successful change, the updater writes the release tag to `.onilink-version` and retains the old file as `OniLink.jar.previous`. To roll back manually, stop the server, rename the current JAR, copy `OniLink.jar.previous` to `OniLink.jar`, and start the server. The next start will install the newest published release again, so rollback is intended for diagnosis while the affected release is corrected or withdrawn.

If the release lookup, download, or checksum validation fails, the updater logs a warning and starts the existing verified JAR. A brand-new server with no usable JAR stops instead of starting an unknown file.

The native candidate and its egg do not make the release production-ready. Complete the exact-profile and live acceptance gates in the [testing guide](../../docs/TESTING.md).
