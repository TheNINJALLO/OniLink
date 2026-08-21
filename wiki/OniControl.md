<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="OniLink control system">
</p>

# OniControl

OniControl is the optional typed operations system built into the existing OniLink dashboard. It is
disabled by default and has three paths:

| Path | Use | Proof of success |
| --- | --- | --- |
| OniPacket | One-player messages, forms, audio/visual packets, and deterministic relay rules | Target client codec dry-encodes the packet |
| OniVirtual | Private menus, entities, holograms, and observed-state fake blocks | Per-connection state and encoded packets |
| ONICTL/1 | Real BDS inventory, teleport, attributes, blocks, and entities | Signed result after OniBridge runs on the primary thread |

## Safe setup

1. Verify normal OniForward joining first.
2. In **Add Backend**, enable the optional control connection and enter a private numeric control IP
   plus TCP port.
3. Download the one-time setup ZIP. It contains different forwarding and control key files and the
   matching `onibridge.toml`.
4. Install it under `/home/container/plugins/onibridge/` and restrict the TCP allocation/firewall to
   the OniLink source.
5. Restart the backend and OniLink. Do not enable actions until the dashboard shows an authenticated
   capability revision.

BDS may use `25570/udp` while OniBridge control uses `25570/tcp`; identical numbers do not conflict
because the transports differ. Do not expose control TCP as a public player allocation.

Every action follows preview, exact target confirmation, and execution. The token expires after 60
seconds and can be used once. New tenant accounts have zero control grants; the provider owner may
grant only the reviewed Operator subset from **Tenant Hosting**.

The current native bridge does not terminate TLS. Use loopback or a private encrypted tunnel across
hosts. Unsupported actions remain visible in the capability response with an exact reason and are
not sent speculatively.

Engineering details and the exact action matrix are in the repository's
[OniControl documentation](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ONICONTROL.md) and
[compatibility table](https://github.com/TheNINJALLO/OniLink/blob/main/docs/COMPATIBILITY.md).
