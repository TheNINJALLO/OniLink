# Minecraft 1.26.50 cross-version preview

The unreleased OniLink build accepts a `1.26.50` protocol-`2192` client and translates it to a BDS
`1.26.44` protocol-`2168` backend. Leave backend detection automatic:

```properties
backend.protocol=auto
```

If the backend cannot answer OniLink's UDP version probe, pin the version BDS actually runs:

```properties
backend.survival.protocol=1.26.44
```

Do not set this to `1.26.50` until BDS itself upgrades. OniLink handles `2192` on the player side
and the `1.26.44` hotfix dialect of `2168` on the backend side. OniBridge and its shared key remain
unchanged and must still match the exact `1.26.44.3` native profile.

This is Preview support, not final-release certification. Test join, chunks, movement, inventory,
crafting, commands, packs, scoreboards, switching, and reconnect after Mojang publishes the final
client. See the canonical
[cross-version guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/CROSS_VERSION_1_26_50.md)
for the complete safety boundary and upstream pins.
