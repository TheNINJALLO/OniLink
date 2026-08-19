<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="OniLink operations dashboard">
</p>

# Operations Dashboard

The responsive control plane is embedded in `OniLink.jar`. It shows real proxy players and backend health, performs live transfers and operator actions, safely edits configuration, and keeps role-based accounts and an audit trail without a separate web service.

> [!WARNING]
> The listener is plain HTTP. Keep the default loopback bind or place remote access behind restricted HTTPS. Never expose it directly to the public Internet.

## First start

Standalone defaults:

```properties
dashboard.enabled=true
dashboard.host=127.0.0.1
dashboard.port=8080
dashboard.sessionMinutes=480
dashboard.dataDirectory=dashboard
```

1. Start OniLink.
2. Open `dashboard/FIRST_RUN_SETUP.txt` and copy the one-time code.
3. Browse to `http://127.0.0.1:8080/` locally.
4. Create the owner with a unique password of at least 12 characters.
5. Enroll TOTP under **Account**.

The setup file is deleted after use. Passwords are stored as salted PBKDF2 hashes.

## Roles

| Role | Intended access |
| --- | --- |
| Viewer | Overview, players, backends, and metrics |
| Operator | Viewer access plus logs, alerts, transfers, disconnects, and packet traces |
| Admin | Operator access plus configuration, audit, endpoints, and support bundles |
| Owner | Admin access plus accounts, tenant hosting, and graceful proxy shutdown |
| Tenant | Only that customer's **My Proxies** page and scoped proxy operations |

Use operator accounts for routine work and reserve the single owner for access administration.

## Tenant hosting

Hosting providers use the owner-only **Tenant Hosting** page to create customer-scoped logins and
isolated proxy listeners inside the existing OniLink container. Give the same Pterodactyl server
one additional UDP allocation per logical proxy. Tenants sign in at this same URL and see only
**My Proxies**; no Application API key, extra server, or repeated egg is needed. See [[Tenant Hosting]].

## Pterodactyl

The released egg maps the primary allocation number to both Bedrock UDP and dashboard TCP. After startup, open `dashboard/FIRST_RUN_SETUP.txt` in the file manager and visit:

```text
http://NODE-OR-DOMAIN:PRIMARY_PORT/
```

If Bedrock works but the page does not, TCP is not published or allowed. Bedrock's UDP path is separate. Persist `dashboard/` with `config.properties` and put the page behind HTTPS before routine remote access.

Use the egg's **Enable operations dashboard** variable to turn the listener off; the panel rewrites the host, port, and enabled properties each start.

## Safe configuration edits

Admins and owners can open the dedicated **Add Backend** page to append a validated route without manually synchronizing two configurations. It is also linked from **Backends** and **Configuration**. The wizard creates OniLink's protected `secrets/<backend>.key`, updates `backends=`, shows the saved proxy properties, and returns the matching key plus complete `onibridge.toml` for `/home/container/plugins/onibridge/` on Endstone. Download both files immediately, start Endstone first, and restart OniLink. See [[Adding Backends]] for the full walkthrough.

The editor redacts protected values, rejects stale revisions, restores secrets from the server-side original, parses the complete proposed configuration, and creates `config.properties.dashboard.bak` before replacement. A restart is required to apply saved settings.

Support bundles also redact configuration secrets, but can contain player names and operational history. Inspect them before sharing.

For HTTPS examples, complete recovery steps, role details, backups, and troubleshooting, use the [full dashboard guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/DASHBOARD.md).
