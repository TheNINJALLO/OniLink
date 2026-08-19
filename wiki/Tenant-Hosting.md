<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="OniLink tenant hosting">
</p>

# Tenant Hosting

OniLink hosts tenant proxies inside the one existing OniLink container. It does not create more
Pterodactyl servers or eggs, and it does not need a Pterodactyl Application API key.

```text
one OniLink container
├── primary UDP port → provider proxy
├── primary TCP port → shared dashboard
├── extra UDP port   → tenant A proxy
└── extra UDP port   → tenant B proxy
```

As the owner:

1. Assign one additional UDP allocation to the existing OniLink Pterodactyl server for each proxy.
2. Sign in to the current OniLink URL and open **Tenant Hosting**.
3. Create a tenant ID, dashboard username, and temporary password.
4. Under **Connect this tenant's proxy**, enter these as four separate connection fields:
   - public proxy IP/domain players join;
   - UDP port assigned to that specific proxy;
   - destination BDS server IP/domain;
   - destination BDS server UDP port.
5. Confirm the page shows `public proxy:port -> destination server:port`, then enter the proxy IP
   that BDS sees without a port.
6. Download the handoff ZIP and install its key plus `onibridge.toml` on the matching Endstone server.

Tenants sign in at the same dashboard URL. Their **My Proxies** page exposes only their own
listeners, players, routes, allowlist, backend wizard, and lifecycle actions. The provider proxy and
other customers remain inaccessible, and cross-tenant requests return HTTP 403.

Each proxy has an isolated configuration, forwarding keys, allowlist, resource-pack cache, and
player registry. All proxies share the container's CPU, memory, and restart boundary, so size the
one server for the combined load.

See the full [single-container tenant hosting guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TENANT_HOSTING.md)
for field examples, port planning, backend installation, backups, migration, and security details.
