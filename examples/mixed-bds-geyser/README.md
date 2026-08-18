# Mixed BDS and Geyser example

This layout uses:

- OniLink at `10.10.0.10:19132/udp`
- Survival BDS + Endstone at `10.10.0.20:19133/udp`
- Geyser at `10.10.0.30:19134/udp`, leading to a Java server
- Unique backend secrets:
  - `ONIBRIDGE_SURVIVAL_SECRET`
  - `ONIBRIDGE_JAVA_SECRET`

Never reuse the survival secret for the Java backend. Each secret must exist on OniLink and only its matching backend validator.

Files:

- `onilink.properties` — complete proxy example.
- `onibridge-survival.toml` — native BDS validator.
- `onibridge-geyser.properties` — Geyser extension validator.
- `geyser-config-fragment.yml` — relevant Geyser listener/authentication settings.
