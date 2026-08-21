# Fake blocks

Fake blocks are per-player visual overrides implemented with the active client block registry. They
do not change BDS, collision, drops, pathfinding, redstone, or persistence.

OniLink accepts an override only after it has observed the authoritative `UpdateBlock` definition
for that exact position and dimension. This restriction is deliberate: without the real definition,
OniLink cannot promise a correct restoration and therefore returns `UNSUPPORTED` instead of guessing.

While an override is active:

- a BDS block update is sent normally and immediately followed by the private visual;
- a resent chunk is followed by every bounded override in that chunk;
- the newest BDS definition is retained as the restoration value;
- restore/clear sends the retained authoritative definition;
- transfer, dimension change, disconnect, and shutdown clear player-scoped state.

Regions are validated before mutation, bounded by `virtualization.maxFakeBlocksPerPlayer`, and
accepted only when every included position has an authoritative observation. Large clear operations
are encoded in bounded batches. Fake blocks must never be represented as real block-edit success.
