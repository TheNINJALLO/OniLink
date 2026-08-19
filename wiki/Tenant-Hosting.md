<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="OniLink tenant hosting">
</p>

# Tenant Hosting

Paid OniLink hosting uses one isolated Pterodactyl server per customer. Do not put unrelated
customers into one embedded dashboard: its roles govern one network and are not tenant boundaries.

Each customer receives one OniLink allocation, process, configuration, dashboard, allowlist, logs,
and set of backend keys. Their BDS servers retain their own UDP allocations, and adding a backend
does not require another OniLink port.

Open **Tenant Hosting** as the owner of the provider's main control plane. From that single page you
can:

1. Save a redacted Pterodactyl Application API connection and select the imported OniLink egg.
2. Discover existing customers, nodes, and currently unassigned allocations.
3. Create Pterodactyl customer logins and reusable resource plans.
4. Create an isolated server idempotently with exactly one primary allocation.
5. Download the private dashboard/backend handoff, synchronize state, and suspend or restore service.

The control plane intentionally cannot delete tenant data and does not expose a public payment
webhook. Your billing system verifies its provider events before an owner applies the documented
grace-period decision. `tools/tenantctl.py` remains available only as an offline or scripted
fallback.

See the full [Commercial tenant hosting guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TENANT_HOSTING.md)
for the panel permissions, exact fields, security requirements, plan setup, storage, and handoff
contents.
