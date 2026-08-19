<p align="center">
  <img src="assets/banner.svg" width="100%" alt="OniLink single-container tenant hosting">
</p>

# Single-container tenant hosting

OniLink can host multiple customer proxies from the **one OniLink server that is already running**.
The owner and every tenant use the same web control plane. OniLink does not create Pterodactyl
servers, import more eggs, or require a Pterodactyl Application API key.

## Architecture

```text
One Pterodactyl server / one OniLink Java process
├── provider proxy       → primary UDP allocation
├── shared web dashboard → primary TCP allocation
├── acme/survival        → additional UDP allocation 19135
├── acme/creative        → additional UDP allocation 19136
└── birch/main           → additional UDP allocation 19137
```

Each logical proxy has its own listener, configuration, backend routes, allowlist, forwarding keys,
resource-pack cache, and live player registry. Tenant accounts are tied to one tenant ID, and the
HTTP API rejects access to every other tenant and to the provider's global proxy controls.

All proxies still share one container and JVM. This makes deployment and updates simple, but it
also means they share the container's CPU, memory, restart schedule, and failure boundary. Use
separate containers only when hard operating-system resource isolation is required.

## Ports and Pterodactyl allocations

Keep the current OniLink primary allocation. It continues to provide:

- Bedrock UDP for the provider's main proxy;
- dashboard TCP on the same number.

Assign **one additional UDP allocation to that same Pterodactyl server for each tenant proxy**.
No extra TCP dashboard port is needed because every tenant uses the shared dashboard URL.

Example:

| Purpose | Address | Transport |
| --- | --- | --- |
| Provider proxy | `45.143.196.108:19130` | UDP |
| Shared dashboard | `https://proxy.example.com/` → TCP `19130` | TCP |
| Acme survival proxy | `45.143.196.108:19135` | UDP |
| Acme BDS backend | `45.143.196.160:25570` | UDP |

In Pterodactyl, open the existing OniLink server, choose **Build Configuration**, and assign the
additional allocations. The owner enters only the numeric port in OniLink. Wings already maps the
assigned port into the container. Ensure the node and provider firewall permit UDP on every tenant
allocation.

## First setup

### 1. Create the tenant login

Sign in to the existing OniLink dashboard as its owner and open **Tenant Hosting**. Under **Create
the tenant and first login**, enter:

| Field | Example | Meaning |
| --- | --- | --- |
| Tenant label | `Acme Network` | Human-readable customer name |
| Tenant ID | `acme` | Stable lowercase security scope |
| Dashboard username | `acme-admin` | Login used at the shared dashboard URL |
| Temporary password | a unique 12+ character password | Initial tenant credential |

Select **Create tenant and login**. Use **Add another tenant user** when more than one person should
operate the same customer's proxies. Do not create ordinary viewer/operator/admin accounts for
customers; those roles apply to the provider proxy. Tenant accounts must be created on this page so
their tenant scope is stored with the account.

### 2. Connect the tenant proxy

Under **Connect this tenant's proxy**, keep the player-facing proxy and destination game server in
their separate sections:

| Field | Example | Meaning |
| --- | --- | --- |
| Tenant | `Acme Network (acme)` | Customer that owns the proxy |
| Proxy ID | `survival` | Stable lowercase ID within that tenant |
| Proxy label | `Survival Proxy` | Display name shown to the customer |
| Public proxy IP or domain | `45.143.196.108` | Player-facing host, without a port |
| Assigned proxy UDP port | `19135` | Additional allocation on this same OniLink server |
| Destination server IP or domain | `45.143.196.160` | Customer's BDS/Endstone server |
| Destination server UDP port | `25570` | UDP allocation assigned to that BDS server |
| Proxy IP seen by the destination server | `45.143.196.108` | Exact source IP BDS observes, without a port |
| Maximum players | `20` | Displayed capacity for this proxy |
| MOTD | `Acme Network` | Bedrock server-list message |
| Approved BDS profile | release profile ID | Matching production OniBridge profile |

Select **Create and start proxy**. OniLink rejects the provider port and any tenant port already in
use. It generates a unique 256-bit forwarding key, writes an isolated runtime directory, and starts
the listener inside the existing JVM.

Before submitting, verify the page shows this direction:

```text
Players connect to this proxy        OniLink forwards them to this server
45.143.196.108:19135            ->   45.143.196.160:25570
```

### 3. Install the backend handoff

Select **Handoff ZIP** beside the proxy. The private archive contains:

```text
CUSTOMER-START-HERE.txt
backend/
├── default.key
└── onibridge.toml
```

On the customer's Endstone server:

1. Install the matching OniBridge `.so` from the same OniLink release.
2. Upload `default.key` and `onibridge.toml` to
   `/home/container/plugins/onibridge/`.
3. Start BDS and confirm OniBridge reports that its production identity hook is active.
4. Start or restart only this proxy from **Tenant Hosting** or **My Proxies**.
5. Join the proxy's public address and verify the backend name appears in the tenant dashboard.

The ZIP contains an authentication key. Never attach it to a public ticket or commit it to a
repository.

## Tenant access

Tenants browse to the **same URL the owner uses**, for example:

```text
https://proxy.example.com/
```

They sign in with the username and temporary password created in **Tenant Hosting**. A tenant login
lands on **My Proxies** and can see only proxies assigned to its tenant. From that page it can:

- start, restart, or stop its logical proxies;
- view connected players and backend routes;
- transfer, trace, or disconnect its players;
- broadcast an alert to one proxy;
- maintain that proxy's authenticated XUID allowlist;
- add another BDS backend and download its generated setup ZIP;
- download the private backend handoff.

The provider's main proxy, configuration, logs, audit log, support bundle, normal account list, and
other tenants are not available to tenant accounts. Cross-tenant API requests fail with HTTP 403.

## Add more proxies and backends

A tenant may have several proxies. Assign one more UDP allocation to the existing Pterodactyl
server, return to **Tenant Hosting**, and create another proxy ID under the same tenant.

A backend route does not need another OniLink allocation. Open the proxy in **My Proxies**, enter
the destination server IP and UDP port separately, confirm the visual connection path, and select
**Generate server setup package**. OniLink creates a different forwarding key, updates only that
proxy, restarts only that listener, and downloads the matched Endstone ZIP.

## Suspension and lifecycle

The owner can suspend a tenant from **Tenant Hosting**. Suspension stops all of that tenant's proxy
listeners and keeps them stopped across container restarts. Restoring the tenant starts every proxy
that was enabled before suspension. Stopping one proxy manually marks only that listener disabled.

OniLink does not process payments, expose an unauthenticated billing webhook, or delete customer
data. A billing system should verify its own events and leave the final suspend/restore decision to
an authenticated owner workflow.

## Storage and backup

```text
dashboard/
├── accounts.properties
└── tenancy/
    ├── catalog.properties
    ├── handoffs/
    │   └── acme--survival.handoff.zip
    └── runtimes/
        └── acme/
            └── survival/
                ├── config.properties
                ├── allowlist.properties
                ├── permissions.properties
                ├── secrets/default.key
                ├── cache/
                └── resource-packs/
```

Back up the complete `dashboard/` directory and restrict it as credential material. On POSIX
systems OniLink writes key and catalog files with owner-only permissions when the filesystem
supports them. Restoring `dashboard/` restores tenant accounts, assignments, keys, allowlists, and
enabled/suspended state.

## Security checklist

- Put the one shared dashboard behind HTTPS and restrict provider-owner access.
- Give each tenant a unique password and encourage TOTP enrollment.
- Allocate a unique UDP port to every logical proxy.
- Use a different generated forwarding key for every backend.
- Restrict each BDS listener to the exact proxy source CIDR.
- Never share tenant handoff ZIPs between customers.
- Size the one container for the combined player load and monitor its memory.
- Back up `dashboard/` before upgrades and test a restore procedure.

## Moving from the retired separate-server workflow

The earlier v0.1.5 tenant hoster stored a Pterodactyl Application API key under
`dashboard/hosting/`. The single-container control plane does not read that directory or contact the
Application API. If it was configured, revoke the old key in Pterodactyl and archive or securely
remove `dashboard/hosting/` after confirming it is no longer needed for rollback.
