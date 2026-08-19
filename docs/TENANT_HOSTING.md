# Commercial tenant hosting

OniLink supports paid hosting through **one isolated Pterodactyl server per customer**. Do not create
customers as users in one shared embedded dashboard: its roles administer one proxy network and are
not tenant boundaries.

## Isolation model

```text
Billing or hosting portal
├── Tenant acme  → OniLink container + one allocation → acme backends only
├── Tenant birch → OniLink container + one allocation → birch backends only
└── Tenant cedar → OniLink container + one allocation → cedar backends only
```

Each tenant receives a separate process, filesystem, configuration, dashboard account store,
allowlist, audit log, player registry, backend routes, and forwarding keys. Added backends therefore
cannot appear in another customer's `/server` list or dashboard.

## Port requirement

Each tenant OniLink instance needs one primary allocation. Bedrock uses it over UDP and the dashboard
uses the same number over TCP. Each BDS backend keeps its own UDP allocation; no backend consumes an
additional port on the tenant's OniLink container.

## Provisioning

Import the current OniLink egg, create the customer's non-administrator Pterodactyl user, and reserve
one allocation. Copy
[`packaging/tenant-hosting/tenantctl.example.json`](../packaging/tenant-hosting/tenantctl.example.json)
to protected operator storage and configure the panel URL, OniLink egg ID, and service plans.

Prepare a private plan and customer handoff:

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

The private plan contains the exact Pterodactyl request and generated secrets. The handoff ZIP
contains the tenant's one-time dashboard setup code plus the matched key/TOML for their default BDS
backend. Both files are created without group/other permissions where the operating system supports
POSIX modes. Treat both as credentials.

Apply the plan with an Application API key supplied only through the environment:

```bash
export PTERODACTYL_APPLICATION_API_KEY='REDACTED'
python tools/tenantctl.py apply \
  --config /secure/onilink-tenantctl.json \
  --plan-file /secure/onilink-tenants/acme/acme.provision.json
```

The external ID makes this operation idempotent. Every generated server receives exactly one primary
allocation and cannot request additional allocations through its plan.

For every option and the handoff contents, read
[`packaging/tenant-hosting/README.md`](../packaging/tenant-hosting/README.md).

## Billing integration

Your billing system must authenticate and verify its own provider webhooks before calling the local
operator commands. OniLink does not expose an unauthenticated billing webhook.

```bash
python tools/tenantctl.py status --config /secure/onilink-tenantctl.json --tenant acme
python tools/tenantctl.py suspend --config /secure/onilink-tenantctl.json --tenant acme
python tools/tenantctl.py unsuspend --config /secure/onilink-tenantctl.json --tenant acme
```

Use suspension after your documented grace period and unsuspend after verified payment recovery.
Cancellation and data deletion should remain separate: `tenantctl` intentionally cannot delete a
server or customer data.

## Security requirements

- Give customers normal Pterodactyl accounts, never panel-administrator access.
- Scope the Application API key to only the read/write permissions the provisioner requires.
- Do not place the API key in the settings JSON, tenant plan, handoff ZIP, egg, or startup variables.
- Restrict each BDS UDP allocation to its expected OniLink source.
- Put dashboard HTTP behind tenant-authenticated HTTPS.
- Never share writable mounts, configuration directories, or secrets between tenant containers.
- Store billing records outside Pterodactyl and use the generated external tenant ID as the link.
