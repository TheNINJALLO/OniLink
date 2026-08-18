# Single BDS example

This layout uses:

- OniLink at `10.10.0.10:19132/udp`
- Survival BDS + Endstone at `10.10.0.20:19133/udp`
- Backend name `survival`
- Bridge ID `survival-main`
- Key ID `key-2026-01`
- Secret environment variable `ONIBRIDGE_SURVIVAL_SECRET`

Copy `onilink.properties` beside `OniLink.jar` as `config.properties`. Copy the native `.so` into the Endstone `plugins/` directory, start once, then replace the generated `plugins/onibridge/onibridge.toml` with the reviewed values from `onibridge.toml`.

The same `ONIBRIDGE_SURVIVAL_SECRET` value must be present in the OniLink process and the BDS/Endstone process.
