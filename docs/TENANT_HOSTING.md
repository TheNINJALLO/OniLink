# Commercial tenant hosting

OniLink's main control plane can create and operate paid customer instances directly through the
Pterodactyl Application API. The supported boundary is **one dedicated OniLink server per
customer**. Dashboard accounts inside one proxy manage that proxy; they are not tenant boundaries.

## What the main panel manages

The owner-only **Tenant Hosting** page keeps the complete operator workflow together:

- the Pterodactyl connection and redacted API-key status;
- customer discovery and optional Pterodactyl user creation;
- reusable memory, disk, CPU, backup, database, and player plans;
- node and unassigned-allocation discovery;
- isolated OniLink server creation;
- setup-package download, status synchronization, suspension, restoration, and failed-request retry.

Customers do not receive access to the provider's main OniLink control plane. They receive a normal
Pterodactyl account and the owner account for their own dedicated OniLink dashboard.

## Isolation model

```text
Provider OniLink control plane (owner only)
├── Tenant acme  → dedicated OniLink container + one allocation → acme backends only
├── Tenant birch → dedicated OniLink container + one allocation → birch backends only
└── Tenant cedar → dedicated OniLink container + one allocation → cedar backends only
```

Each customer has a separate process, filesystem, `config.properties`, dashboard account store,
allowlist, audit log, player registry, backend list, and forwarding key. A backend added to one
tenant cannot appear in another tenant's dashboard or `/server` list.

## 1. Prepare Pterodactyl

1. Import the current [`egg-onilink.json`](../packaging/pterodactyl/egg-onilink.json).
2. Make sure each Wings node has unused allocations that can be assigned to customer proxies.
3. Create a Pterodactyl **Application API** key for the provider account.
4. Grant the key server read/write, user read/write, and read access to nodes, allocations, nests,
   and eggs. User write is optional only when customer accounts will always be created elsewhere.
5. Put the provider's OniLink dashboard behind HTTPS and restrict it to provider administrators.

The provider control plane makes outbound HTTPS requests to the panel. The Pterodactyl URL must be
an HTTPS origin such as `https://panel.example.com`, without `/admin`, `/api`, a query string, or
embedded credentials.

## 2. Connect from OniLink

Sign in to the provider's dashboard as its owner and open **Tenant Hosting**.

1. Enter the Pterodactyl URL and Application API key.
2. Select **Save and load Pterodactyl**.
3. OniLink verifies node access and loads customers, nodes, nests, and eggs.
4. If exactly one discovered egg is named OniLink, it is selected automatically. Otherwise select
   the imported OniLink egg and save again.
5. Confirm the badge shows **READY**.

The key is sent only to the provider OniLink server, stored in
`dashboard/hosting/settings.properties`, and never returned by the dashboard API. The page shows
only a short ending hint. Leaving the key field blank preserves the saved key; entering a new value
rotates it.

> [!IMPORTANT]
> The embedded dashboard speaks HTTP. Complete the HTTPS reverse-proxy setup before entering a real
> panel key over an untrusted network.

## 3. Create plans and customers

The built-in `starter` plan is immediately usable. Select **Add or update a plan** to change it or
create another plan. Plan IDs use lowercase letters, numbers, and hyphens; entering an existing ID
updates that plan. An assigned plan cannot be removed, and every tenant receives an additional
allocation limit of zero regardless of the plan.

If the customer is not already in the customer dropdown, use **Create Pterodactyl login**. The
temporary password goes directly to Pterodactyl and is not stored by OniLink. The created account is
selected automatically for the next server.

Give customers normal Pterodactyl accounts, never panel-administrator or provider-dashboard access.

## 4. Provision a tenant

Complete **Create an isolated OniLink server**:

| Field | Meaning |
| --- | --- |
| Customer | The Pterodactyl account that will own this server |
| Hosting plan | The resources and feature limits applied at creation |
| Customer label | A readable provider-side name, such as `Acme Network` |
| Tenant ID | Stable lowercase ID used in `onilink-tenant-<id>` |
| Pterodactyl node | The Wings node that will run the customer proxy |
| Available OniLink allocation | A live, unassigned allocation loaded from that node |
| Customer BDS address | The existing BDS UDP endpoint reachable from the new proxy |
| Proxy source IP seen by BDS | Exact egress/NAT address BDS sees; blank uses the allocation IP |

Select **Provision tenant and build handoff**. OniLink then:

1. rechecks that the selected allocation is still unassigned;
2. creates unique forwarding and one-time dashboard setup secrets;
3. saves a recoverable provisioning record before contacting Pterodactyl;
4. creates one Pterodactyl server with one primary allocation and no additional allocations;
5. passes the egg's protected startup variables, including the matching forwarding secret;
6. records the returned Pterodactyl server ID and UUID;
7. creates a private handoff ZIP for the customer/backend administrator.

The operation uses the stable external ID `onilink-tenant-<tenant>`. If the API response is
interrupted after Pterodactyl creates the server, **Retry** finds that external ID and reconciles it
instead of creating a second server.

## Port assignment

Each tenant OniLink server needs exactly one primary allocation:

```text
tenant allocation / UDP → Bedrock player listener
same number / TCP       → tenant OniLink dashboard
tenant BDS allocation   → backend Bedrock server
additional bridge port  → not required
```

TCP and UDP can use the same number. The Wings mapping and network firewall must allow both when the
tenant dashboard will be reachable. Each BDS server retains its own UDP allocation.

## 5. Install the handoff

Select **Handoff ZIP** beside the tenant. It contains:

```text
CUSTOMER-START-HERE.txt
backend/
├── default.key
└── onibridge.toml
```

Install the matching OniBridge `.so` from the same OniLink release on the customer's BDS server,
then upload `default.key` and `onibridge.toml` to
`/home/container/plugins/onibridge/`. Start BDS first and confirm the production hook is active;
then start the tenant OniLink server. The customer uses the ZIP's one-time setup code to create the
owner of their own dashboard.

The ZIP contains credentials. Send it through a protected channel and never attach it to a public
ticket or repository.

## 6. Operate the customer fleet

- **Sync** reads the current server ID, UUID, status, and suspension state from Pterodactyl.
- **Suspend** stops service through Pterodactyl after the provider's billing/grace-period decision.
- **Restore** removes that suspension after payment recovery.
- **Retry** resumes a prepared or failed provisioning record without rotating the saved handoff.
- **Refresh list** synchronizes every displayed tenant.

Deletion is intentionally absent. Cancellation, backup retention, and permanent deletion require a
separate deliberate provider process so a billing mistake cannot erase customer data.

OniLink does not expose a public billing webhook. A billing platform must authenticate and verify
its own events before an owner makes a lifecycle decision or before a future private integration
calls these authenticated controls.

## Storage and backup

```text
dashboard/hosting/
├── settings.properties   # panel key, selected egg, startup values, and plans
├── tenants.properties    # tenant metadata plus recovery copies of generated secrets
└── handoffs/
    └── <tenant>.handoff.zip
```

POSIX deployments set these files to owner read/write (`0600`). Back up the entire `dashboard/`
directory as credential material. Anyone who can read this storage can administer the configured
Pterodactyl resources and impersonate a proxy to a tenant backend.

## Security checklist

- Keep the provider control plane behind restricted HTTPS and enable TOTP for its owner.
- Use the narrowest compatible Application API permissions and rotate the key after exposure.
- Restrict each BDS UDP allocation to the exact tenant proxy source CIDR.
- Never reuse a handoff, forwarding key, writable mount, or configuration directory across tenants.
- Keep customer billing records outside OniLink and link them with the stable tenant ID.
- Audit **Tenant Hosting** actions in the provider dashboard before resolving disputes.

## CLI fallback

`tools/tenantctl.py` remains in release bundles for offline recovery and scripted environments. It
uses the same dedicated-server model, but it is no longer the normal setup path. Operators should
use the owner-only main panel unless the dashboard itself is unavailable.
