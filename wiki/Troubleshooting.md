# Troubleshooting

## Backend shuts down at startup

Read the first OniBridge critical message. Common causes are a missing secret, empty/wrong profile ID, wrong BDS hash, wrong Endstone version, expected-byte mismatch, unknown TOML key, or an attempted unapproved profile.

Do not turn off shutdown-on-hook-failure or enable unknown-runtime bypasses.

## Loader reports `GLIBC_x.y not found`

Install the current release `.so`, whose build gate rejects imports newer than `GLIBC_2.35`, and restart BDS. The current amd64 `ghcr.io/parkervcp/yolks:python_3.14` image is Debian Bookworm/glibc 2.36 and does not need to be replaced. Do not copy a newer `libc.so.6` into the container.

```bash
ldd --version | head -n 1
readelf --version-info plugins/onibridge-*.so | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -n 1
```

An undefined `std::__cxx11` loader symbol is a mixed C++ runtime, not a missing glibc version. Install the latest release; do not add `libstdc++.so.6` to an Endstone/libc++ process. Release automation accepts a runtime-neutral plugin or libc++ references and rejects every `libstdc++`, `__cxx11`, or `GLIBCXX` contaminant.

## Every forwarded login is rejected

Compare backend name, bridge ID, key ID, Base64 secret, clock, token lifetime, and observed proxy source CIDR on both sides.

## Direct join works

Treat this as a security failure. Confirm `reject_direct_joins=true`, verify OniBridge is active, and firewall the backend listener to OniLink.

## Every player is reported as not allow-listed

Use the OniLink/Pterodactyl console to run `allowlist status`, then `allowlist add <XUID> <label>`. An enabled empty list denies everyone, and proxy administrators do not bypass it. The authenticated dashboard's **Allowlist** page can also recover access; otherwise temporarily set `allowlist.enabled=false` and restart.

## First join works but rejoin has empty data

The verified XUID was not active before BDS selected storage. Stop testing and inspect hook/profile evidence; this is not a cache problem.

More cases are in [docs/TROUBLESHOOTING.md](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TROUBLESHOOTING.md).
