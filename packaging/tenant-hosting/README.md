# Isolated tenant hosting

OniLink's supported commercial-hosting model is one Pterodactyl server per customer. Each tenant
gets one proxy process, one primary allocation, one dashboard account store, and only that tenant's
backend routes and forwarding keys. Dashboard roles inside a proxy are not a cross-customer security
boundary.

The owner-only **Tenant Hosting** page in OniLink's main control plane is the normal provisioning
workflow. It discovers Pterodactyl customers, nodes, eggs, and free allocations; manages plans;
creates isolated servers; and provides lifecycle controls in one place. See
[`docs/TENANT_HOSTING.md`](../../docs/TENANT_HOSTING.md) for that guided setup.

`tools/tenantctl.py` is the offline recovery and scripted-automation fallback. It prepares and
provisions the same isolated instances through Pterodactyl's Application API and never places
multiple customers in one `config.properties` file.

The GitHub release ships `tenantctl.py`, `tenantctl.example.json`, and this guide as separate
checksum-covered assets. Commands below use the cloned-repository path; when using the release
assets from one directory, replace `tools/tenantctl.py` with `tenantctl.py`.

## CLI fallback prerequisites

- Import the current `packaging/pterodactyl/egg-onilink.json` into the panel.
- Create a non-administrator Pterodactyl user for the customer.
- Reserve exactly one unused allocation for the customer's OniLink instance.
- Give the customer's BDS server its own UDP allocation.
- Create a Pterodactyl Application API key with only the server read/write permissions used by the
  provisioner.
- Keep the API key only in `PTERODACTYL_APPLICATION_API_KEY`; never put it in the JSON settings.

Copy `tenantctl.example.json` outside the public web root and replace `panel_url` and `egg_id`.
Resource plans are operator policy and can be renamed or adjusted.

## Prepare a tenant

```bash
python tools/tenantctl.py prepare \
  --config /secure/onilink-tenantctl.json \
  --tenant acme \
  --user-id 42 \
  --allocation-id 310 \
  --proxy-address 45.143.196.108:19140 \
  --backend-address 198.51.100.20:25571 \
  --plan starter \
  --output-directory /secure/onilink-tenants/acme
```

`--proxy-address` is the one public allocation players use. `--backend-address` is the existing BDS
UDP allocation. If BDS observes a different egress address because of NAT, add
`--proxy-source-ip <numeric-ip>`.

Preparation creates two owner-only files:

- `acme.provision.json` contains the exact API request and generated secrets. It is an operator
  recovery record and must never be given to another tenant.
- `acme.handoff.zip` contains the tenant's one-time dashboard setup code and the matching
  `default.key` and `onibridge.toml` for their backend.

The handoff ZIP does not contain OniBridge itself. Install the matching `.so` from the same OniLink
release so native compatibility remains explicit.

## Apply the plan

```bash
export PTERODACTYL_APPLICATION_API_KEY='application-api-key-from-the-panel'
python tools/tenantctl.py apply \
  --config /secure/onilink-tenantctl.json \
  --plan-file /secure/onilink-tenants/acme/acme.provision.json
```

Provisioning is idempotent by the external ID `onilink-tenant-<tenant>`. Retrying does not create a
second server. The generated server receives exactly one primary allocation and an additional
allocation limit of zero.

## Billing lifecycle

A payment service should call these only after it has authenticated and verified its own webhook.
`tenantctl` deliberately does not accept public webhooks itself.

```bash
python tools/tenantctl.py status --config /secure/onilink-tenantctl.json --tenant acme
python tools/tenantctl.py suspend --config /secure/onilink-tenantctl.json --tenant acme
python tools/tenantctl.py unsuspend --config /secure/onilink-tenantctl.json --tenant acme
```

Recommended policy:

1. Successful first payment prepares and applies the tenant plan.
2. A verified past-due event starts your grace period; it should not immediately delete anything.
3. A verified suspension decision calls `suspend`.
4. A verified recovery payment calls `unsuspend`.
5. Cancellation retains backups for your documented retention period. Deletion remains a separate,
   deliberate operator action and is not implemented by `tenantctl`.

## Isolation guarantees

- The panel owns the tenant-to-server relationship.
- Each server has a separate filesystem, process, dashboard account file, audit log, allowlist, and
  forwarding secrets.
- The customer cannot add an allocation through this plan.
- Backends added through the dashboard exist only in that customer's proxy configuration.
- No customer account is created in another customer's embedded dashboard.

Node and firewall isolation are still operator responsibilities. Restrict BDS UDP allocations to the
expected proxy source, put dashboard HTTP behind restricted HTTPS, and do not share writable mounts
between tenant containers.
