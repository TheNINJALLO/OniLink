# Private entities

Private entities exist only in one authenticated client's synthetic entity namespace. OniLink
allocates IDs outside the backend mapping, verifies the identifier exists in the entity registry
sent to that client, dry-encodes every spawn/update/move/remove packet, and never forwards their IDs
to BDS.

Reviewed actions are generic spawn, update metadata, move, remove, clear, private NPC, and private
hologram. Metadata is allowlisted to name, scale, no-AI, silent, gravity, visibility, and nameplate
flags. Arbitrary entity metadata, attributes, links, NBT, equipment, riding, and animation data are
not accepted by this path.

Optional interactions are consumed before the backend relay and invoke one typed action recorded at
creation. The creator's account and role are retained; clicking cannot elevate authority. Entities
are bounded per player and removed on expiry checks, disconnect, backend transfer, dimension change,
or runtime shutdown.

An entity identifier absent from that client's negotiated registry returns `UNSUPPORTED`. This is
why support can differ by player protocol or initial backend.
