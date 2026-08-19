# OniLink Pterodactyl egg

`egg-onilink.json` is the importable PTDL v2 egg for the OniLink proxy process. It does not redistribute BDS and it does not replace the separate BDS/Endstone or Geyser backend server.

## What the egg does

- Runs OniLink on a Java 21 Pterodactyl image.
- Downloads an exact public OniLink bootstrap release during installation or reinstall.
- Downloads `SHA256SUMS` and verifies `OniLink.jar`, `start-onilink.sh`, and the configuration template before installation succeeds.
- Checks the selected stable, beta, or pinned GitHub release channel on every container start.
- Verifies and atomically updates `OniLink.jar`, the updater, and the reference configuration while preserving the live configuration.
- Keeps the previous JAR and changed runtime support files for rollback.
- Starts the installed JAR when GitHub is temporarily unavailable or an update cannot be verified.
- Creates `config.properties` only when it is missing, preserving operator changes during reinstall.
- Maps the primary Pterodactyl allocation to Bedrock UDP and dashboard TCP on the same numeric port.
- Persists dashboard accounts/audit data and provides an operator switch to disable the web listener.
- Maps the backend host, backend port, MOTD, and displayed player limit into the default configuration.
- Exposes a safe-off switch for OniLink's authenticated XUID allowlist.
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
10. Add your own XUID in **Dashboard → Allowlist**, then enable the egg's allowlist switch and restart.

The initial `127.0.0.1` backend host works only when a backend is deliberately in the same container, which is not the recommended layout. Change it to the private hostname or address reachable through Wings networking.

## Multiple backends

The egg exposes optional `ONIBRIDGE_SURVIVAL_SECRET` and `ONIBRIDGE_JAVA_SECRET` variables for the repository's mixed example. Edit `config.properties` using the [mixed BDS/Geyser example](../../examples/mixed-bds-geyser/onilink.properties), then set both admin-only variables to different secret values.

Pterodactyl rewrites only these fields at startup:

- `listener.host`
- `listener.port`
- `dashboard.enabled`, `dashboard.host`, and `dashboard.port`
- `allowlist.enabled`
- `backend.host` and `backend.port`
- `backend.default.host` and `backend.default.port`
- `motd`
- `maxPlayers`

All other fields remain under direct operator control in `config.properties`.

The dashboard is reachable at `http://NODE-OR-DOMAIN:PRIMARY_PORT/` when **Enable operations dashboard** is true and TCP is published. Use a trusted network for first-run setup, then place it behind restricted HTTPS. Bedrock and the dashboard share a number without conflict because Bedrock binds UDP and the dashboard binds TCP. Persist `dashboard/` and protect it as credential material. The [dashboard guide](../../docs/DASHBOARD.md) covers roles, TOTP, reverse proxies, backups, and recovery.

The optional admin-only `ONILINK_DASHBOARD_SETUP_CODE` variable supports automated provisioning. A
blank value keeps the normal randomly generated `dashboard/FIRST_RUN_SETUP.txt` flow. Never make the
variable customer-editable or reuse a setup code between instances.

For tenant hosting, keep this one egg-created OniLink server and assign it one additional UDP
allocation per logical customer proxy. Create scoped logins and listeners from **Tenant Setup** in
the shared dashboard. No Pterodactyl Application API key, extra server, or repeated egg is needed.
See the [single-container tenant guide](../../docs/TENANT_HOSTING.md).

## Automatic updates and rollback

Restart the container to check for an update. `start-onilink.sh` resolves **Automatic update channel**, downloads `OniLink.jar`, `start-onilink.sh`, `onilink.properties.example`, and `SHA256SUMS`, verifies all three runtime files, and only then installs changes.

- `stable` uses GitHub's `/releases/latest` endpoint and ignores prereleases.
- `beta` follows the newest published normal or prerelease build. The `v0.2.0-beta.1` egg defaults to this channel.
- `pinned` stays on the exact public tag in `ONILINK_VERSION`.

`ONILINK_VERSION` is the bootstrap version used during server creation or **Reinstall Server** and the runtime version only when the channel is `pinned`. Reinstall preserves `config.properties`; compare it with `onilink.properties.example` after a bootstrap upgrade.

After a successful change, the updater writes the release tag to `.onilink-version` and retains the old JAR as `OniLink.jar.previous`. A changed updater is saved as `start-onilink.sh.previous` and takes effect on the following restart; a changed reference template is saved as `onilink.properties.example.previous`. Active `config.properties` is never replaced. To hold a rollback, set the channel to `pinned` and set `ONILINK_VERSION` to the intended release before restarting.

The runtime updater cannot replace the egg definition stored by the Pterodactyl panel. Reimport a newer `egg-onilink.json` when you want newly added panel variables or install-script changes. Existing servers can still receive new runtime JARs without an egg reimport.

An existing `v0.1.7` updater is stable-only and cannot discover a prerelease. Back up the server,
reimport the beta egg, confirm the `beta` channel, and run **Reinstall Server** once to bootstrap the
beta JAR and channel-aware updater. Reimporting without reinstalling does not change the existing
`/home/container/start-onilink.sh`. After that one bootstrap, ordinary reboots follow newer betas.

If the release lookup, download, or checksum validation fails, the updater logs a warning and starts the existing verified JAR. A brand-new server with no usable JAR stops instead of starting an unknown file.

No application update channel overrides native-profile approval. Complete the exact-profile and live acceptance gates in the [testing guide](../../docs/TESTING.md).
