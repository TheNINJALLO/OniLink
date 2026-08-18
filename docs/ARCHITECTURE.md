# Architecture

The public Bedrock client terminates at OniLink. The proxy validates the Mojang/Xbox certificate chain, retains the authenticated name/XUID/UUID, chooses a backend, and forges the offline login format appropriate to that backend protocol. Immediately before signing client data it adds one backend-bound OniForward token carrying the authenticated identity and real endpoint.

OniBridge runs in the unmodified Endstone process. Endstone's public `PacketReceiveEvent` supplies the final raw Login payload and actual transport source; OniBridge parses the client-data JWT and locally validates HMAC/context/time/source/replay before staging a single-use identity. The exact native call-site hook then consumes that identity after BDS has built `PlayerAuthenticationInfo` but before the result reaches Endstone or BDS storage selection. It substitutes only XUID, calls the original BDS move helper, and preserves the backend UUID. A post-construction check disconnects on any XUID mismatch.

```text
client -> OniLink auth/routing -> offline login + OniForward -> OniBridge pre-storage hook -> BDS storage
                                  backend command packets <-----------------------------> unchanged
```

The compatibility plane is separate: `bdsctl` locks official archives; `sdkgen` inspects independently per OS; minimal ABI declarations and hook profiles are reviewed and tested. Unknown hashes, signatures, layouts, detours, or chain behavior prevent hook installation.
