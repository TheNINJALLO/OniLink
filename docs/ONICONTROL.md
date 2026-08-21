# OniControl

OniControl is OniLink's optional typed control system. It combines three execution planes without
weakening Xbox authentication or OniForward identity forwarding:

```text
authenticated player
       |
       +-- CLIENT_ONLY --------> OniPacket ----> that player's negotiated codec
       +-- VIRTUALIZED --------> OniVirtual ---> that player's private client state
       `-- BACKEND_AUTHORITATIVE -> ONICTL/1 -> OniBridge -> BDS primary thread
```

All control, packet-rule, virtualization, and Protocol Lab gates default to `false`. Normal proxy
forwarding follows its existing path when they are disabled.

## Execution planes

| Plane | Meaning | Success evidence |
| --- | --- | --- |
| `CLIENT_ONLY` | Presentation sent to one authenticated connection | Packet dry-encoded with that client's codec and queued to that client only |
| `VIRTUALIZED` | State owned by OniLink and visible only to one connection | Session/entity/block state recorded and its packets encoded successfully |
| `BACKEND_AUTHORITATIVE` | Real persistent BDS state | Signed OniBridge result after the action ran on the Endstone primary thread |

A client packet never proves an authoritative inventory change or teleport. Missing capabilities,
stale targets, transfers in progress, expired confirmation tokens, and unavailable bridges fail
without changing state.

## Setup

1. Configure and verify OniForward first.
2. Allocate a private TCP route from OniLink to the backend. TCP may use the same number as BDS UDP.
3. In **Dashboard -> Add Backend**, select **Generate the optional OniControl connection**.
4. Enter the backend's private control IP and TCP port; begin in `advisor` mode.
5. Download the one-time ZIP. It contains independent forwarding and control keys plus one matching
   `onibridge.toml`.
6. Install both keys and the TOML in `/home/container/plugins/onibridge/` with the matching `.so`.
7. Restrict the TCP listener to the OniLink source in both the firewall and
   `control.trusted_proxy_cidrs`.
8. Restart OniBridge and OniLink. Confirm the dashboard shows a signed capability revision before
   enabling actions.

The setup wizard accepts only loopback/private numeric control addresses. It will not generate a
public cleartext deployment.

## Operator flow

Every action uses `validate -> preview -> confirm -> execute`. Preview resolves a display name to
one XUID/connection and freezes XUID, connection ID, backend, protocols, tenant, and proxy scope.
The confirmation token is random, stored only as a digest, expires after 60 seconds, and is single
use. Destructive actions require an explicit confirmation value.

Plans use the same boundary at `/api/control/plans/*`. A plan contains at most 16 typed steps and
uses `STOP_ON_FAILURE`, `CONTINUE_ON_FAILURE`, or `COMPENSATE_WHEN_POSSIBLE`. A plan crossing a
network boundary is never described as atomic; completed and failed steps are returned separately.
No compensation is claimed unless a reviewed compensation step exists.

Cross-backend transfer-and-then-mutate orchestration is not implemented: a preview freezes the
current backend, and a later step rejects if the player transfers. Use the existing transfer flow,
wait for the destination world, then create a new preview. Virtual commands, entity replacement,
cosmetics, borders, scenes, unrestricted backend commands, several authoritative Endstone
operations, and native TLS remain `UNSUPPORTED`. Protocol Lab is now implemented as an Owner-only,
disabled-by-default allowlisted semantic test surface; see [Protocol Lab](PROTOCOL_LAB.md). The exact
[capability matrix](COMPATIBILITY.md) distinguishes implemented, limited, candidate, and disabled
paths.

## Roles and tenants

- Viewer: status, capabilities, and non-secret history.
- Operator: presentation, tracing, approved virtual interactions, transfer, and kick.
- Admin: packet rules, reviewed private entities/fake blocks, and scoped operations modules.
- Owner: key/transport settings, fleet promotion/rollback, tenant grants, and Protocol Lab.
- Tenant: only its assigned runtime and only operator actions explicitly granted by the owner.

New tenants have zero OniControl grants. Provider owners manage the allowlist in **Tenant Hosting ->
Tenant OniControl grants**. Admin/owner actions cannot be granted through that interface.

## Related reference

- [OniPacket](ONIPACKET.md)
- [Virtual inventories](VIRTUAL_INVENTORIES.md)
- [Private entities](PRIVATE_ENTITIES.md)
- [Fake blocks](FAKE_BLOCKS.md)
- [Security and ONICTL/1](ONICONTROL_SECURITY.md)
- [Dashboard API](ONICONTROL_API.md)
- [Pterodactyl](ONICONTROL_PTERODACTYL.md)
- [Compatibility matrix](COMPATIBILITY.md)
