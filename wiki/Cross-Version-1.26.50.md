# Minecraft 1.26.50 cross-version preview

OniLink beta 7 accepts a `1.26.50` protocol-`2192` client and translates it to BDS `1.26.45.1`
protocol `2169`, or to an older supported backend. Leave backend detection automatic:

```properties
backend.protocol=auto
```

If the backend cannot answer OniLink's UDP version probe, pin the version BDS actually runs:

```properties
backend.survival.protocol=1.26.45
```

Do not set this to `1.26.50` until BDS itself upgrades. OniLink handles `2192` on the player side
and `2169` on a BDS 1.26.45 backend. Existing keys remain valid, but OniBridge must be rebuilt for
exact BDS `1.26.45.1` and Endstone `0.11.10`; a 1.26.44 native plugin cannot be reused.

This is Preview support, not final-release certification. Test join, chunks, movement, inventory,
crafting, commands, packs, scoreboards, switching, and reconnect after Mojang publishes the final
client. See the canonical
[cross-version guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/CROSS_VERSION_1_26_50.md)
for the complete safety boundary and upstream pins.
