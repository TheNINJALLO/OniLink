# Troubleshooting

## Backend shuts down at startup

Read the first OniBridge critical message. Common causes are a missing secret, empty/wrong profile ID, wrong BDS hash, wrong Endstone version, expected-byte mismatch, unknown TOML key, or disabled candidate opt-in.

Do not turn off shutdown-on-hook-failure or enable unknown-runtime bypasses.

## Every forwarded login is rejected

Compare backend name, bridge ID, key ID, Base64 secret, clock, token lifetime, and observed proxy source CIDR on both sides.

## Direct join works

Treat this as a security failure. Confirm `reject_direct_joins=true`, verify OniBridge is active, and firewall the backend listener to OniLink.

## First join works but rejoin has empty data

The verified XUID was not active before BDS selected storage. Stop testing and inspect hook/profile evidence; this is not a cache problem.

## Geyser reports an unused SubChunk request

Set `backend.<name>.dropSubChunkRequests=true` for the Geyser backend.

## Geyser rejects address access

The installed Geyser build changed an internal access point. The extension fails closed by design. Use the documented Geyser target or update and review the compatibility adapter.

More cases are in [docs/TROUBLESHOOTING.md](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TROUBLESHOOTING.md).
