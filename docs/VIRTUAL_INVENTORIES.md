# Virtual inventories

OniVirtual menus are client-only button containers, not BDS inventories. Supported sizes are 9, 27,
and 54 slots. Each open generates a player-scoped container ID and fresh network stack IDs. Items
come from the active client item registry and may include a bounded display name and lore.

Safety rules:

- Virtual items never enter the real inventory.
- Real items never move into a virtual menu.
- Item stack requests, inventory transactions, equipment packets, and slot mutations are consumed
  while the menu owns the container.
- Request IDs and network stack IDs are validated; replayed or unknown IDs receive an error response.
- Backend inventory resync packets are retained in a bounded buffer and replayed after close.
- Menus close on timeout interaction, client close, operator close, disconnect, transfer, dimension
  change, or runtime shutdown.
- Slot hooks contain a typed `ActionType` and validated payload. They inherit the creating actor and
  role and cannot introduce raw packet data.

The current reviewed implementation is intentionally a non-mutating button menu. Drag/drop menus or
virtual crafting are `UNSUPPORTED`; no packet-only success is returned for them.

Example action payload:

```json
{
  "title": "Server selector",
  "size": 27,
  "timeoutMillis": 120000,
  "slots": [
    {
      "index": 13,
      "identifier": "minecraft:compass",
      "count": 1,
      "name": "Survival",
      "lore": ["Click to transfer"],
      "action": "SEND_MESSAGE",
      "actionPayload": { "message": "Selection accepted" }
    }
  ]
}
```
