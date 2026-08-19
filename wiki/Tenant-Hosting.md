<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="OniLink tenant hosting">
</p>

# Tenant Hosting

Paid OniLink hosting uses one isolated Pterodactyl server per customer. Do not put unrelated
customers into one embedded dashboard: its roles govern one network and are not tenant boundaries.

Each customer receives one OniLink allocation, process, configuration, dashboard, allowlist, logs,
and set of backend keys. Their BDS servers retain their own UDP allocations, and adding a backend
does not require another OniLink port.

Use `tools/tenantctl.py` to:

1. Generate a protected Pterodactyl plan and customer handoff ZIP.
2. Create the isolated server idempotently through the Application API.
3. Assign exactly one primary allocation and the selected resource plan.
4. Generate the dashboard setup code and matched default-backend configuration.
5. Suspend or unsuspend the tenant after a verified billing decision.

The tool intentionally cannot delete tenant data and does not expose a public payment webhook. Your
billing system verifies its provider events and calls the local suspend/unsuspend commands after your
grace-period policy.

See the full [Commercial tenant hosting guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TENANT_HOSTING.md)
for commands, security requirements, plan configuration, and handoff contents.
