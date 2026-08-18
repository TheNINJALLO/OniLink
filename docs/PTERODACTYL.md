# Pterodactyl deployment

Use a separate Pterodactyl server for OniLink and for every BDS or Geyser backend. The proxy allocation is public; backend allocations are private or firewalled to the proxy.

## Import the OniLink egg

Download `egg-onilink.json` from the [current release](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.1.0-candidate.1) or [`packaging/pterodactyl`](../packaging/pterodactyl/README.md).

1. Open **Admin Panel → Nests**.
2. Select or create a nest and choose **Import Egg**.
3. Upload `egg-onilink.json`.
4. Create a server using **OniLink Bedrock Proxy** and the Java 21 image.
5. Assign one public UDP allocation. The egg writes its primary port to `listener.port`.
6. Set **Default backend host** and **Default backend UDP port** to the private address reachable from the OniLink container.
7. Generate a forwarding secret with `openssl rand -base64 32` and enter it in the admin-only **Default OniForward secret** variable.
8. Put the identical value in the default backend validator.
9. Start the backend and confirm its validator first; then start OniLink.

The installer downloads the exact `ONILINK_VERSION` bootstrap tag, verifies `OniLink.jar`, `start-onilink.sh`, and the configuration template against that release's `SHA256SUMS`, and preserves an existing `config.properties` during reinstall. Every later container start checks the newest published GitHub release, including prereleases, and installs its JAR only after checksum validation.

If GitHub is unavailable or verification fails, startup keeps the currently installed JAR. A successful replacement retains the prior version as `OniLink.jar.previous` and records the active release tag in `.onilink-version`.

The egg covers only the OniLink proxy process. It cannot redistribute BDS. Use an existing licensed BDS/Endstone server or egg for the native backend, and Geyser's official standalone egg or an existing Geyser server for the Java path.

## Recommended server layout

| Panel server | Example allocation | Public? | Persistent data |
| --- | --- | --- | --- |
| OniLink | `19132/udp` | Yes | `config.properties`, `cache/`, `logs/`, packs |
| Survival BDS | `19133/udp` | No | Worlds, BDS config, Endstone plugins/data |
| Geyser | `19134/udp` | No | Geyser/Floodgate config, extensions/data |

Do not assign the backend allocation as a public/player address. If the panel cannot create a truly private allocation, enforce the source boundary at the node/provider firewall.

## Administrator-only secret variables

Generate one different Base64 secret per backend. Add it as a non-user-viewable environment variable to exactly two Pterodactyl servers: OniLink and that backend. Panel administrators can still access server variables, so restrict panel administration and protect panel/database backups.

| Backend | OniLink variable | Backend variable |
| --- | --- | --- |
| Survival BDS | `ONIBRIDGE_SURVIVAL_SECRET` | `ONIBRIDGE_SURVIVAL_SECRET` |
| Geyser/Java | `ONIBRIDGE_JAVA_SECRET` | `ONIBRIDGE_JAVA_SECRET` |

The names and values must match within each row. Values must differ between rows.

Do not bake secrets into an egg, image, startup command, Git repository, or public variable default.

The released egg declares these without values:

| Egg variable | Use |
| --- | --- |
| `ONIBRIDGE_FORWARDING_SECRET` | Required by the shipped one-backend template |
| `ONIBRIDGE_SURVIVAL_SECRET` | Optional named variable for the mixed example |
| `ONIBRIDGE_JAVA_SECRET` | Optional named variable for the mixed example |

When using the mixed example, the survival and Java values must be different. An egg export contains only blank defaults; never add a real value to `egg-onilink.json`.

## OniLink server files

Place these in the OniLink container root:

```text
/home/container/
├── OniLink.jar
├── config.properties
├── cache/
├── logs/
└── resource-packs/        # optional
```

Manual startup command without automatic updates:

```bash
java -jar OniLink.jar config.properties
```

The egg starts through the released updater, which launches the equivalent memory-aware command after its release check:

```bash
bash ./start-onilink.sh
```

Relevant configuration:

```properties
listener.host=0.0.0.0
listener.port=19132
publicAddress=play.example.com:19132

backend.name=survival
backends=survival,java
hubBackend=survival

backend.survival.host=10.10.0.20
backend.survival.port=19133
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET

backend.java.host=10.10.0.30
backend.java.port=19134
backend.java.dropSubChunkRequests=true
backend.java.forwarding.enabled=true
backend.java.forwarding.bridgeId=java-main
backend.java.forwarding.activeKeyId=key-2026-01
backend.java.forwarding.activeSecretEnv=ONIBRIDGE_JAVA_SECRET
```

Use the actual private addresses/routes reachable between Wings containers. A panel allocation address is not always the same address another container can reach.

## Native BDS server files

The relevant layout is:

```text
/home/container/
├── bedrock_server
├── plugins/
│   ├── onibridge-0.1.0-bds-1.26.44.3-linux-x86_64.so
│   └── onibridge/
│       └── onibridge.toml
└── worlds/
```

The panel startup process must inherit `ONIBRIDGE_SURVIVAL_SECRET`. The TOML uses only the variable name:

```toml
bridge_id = "survival-main"
backend_name = "survival"
trusted_proxy_cidrs = ["10.10.0.10/32"]

[forwarding]
active_key_id = "key-2026-01"
active_secret_env = "ONIBRIDGE_SURVIVAL_SECRET"

[compatibility]
required_profile = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"
allow_unreviewed_profile = true
allow_unknown_bds = false
allow_unknown_endstone = false
```

Use the complete TOML from [`examples/single-bds/onibridge.toml`](../examples/single-bds/onibridge.toml), not only this excerpt.

## Geyser server files

```text
/home/container/
└── extensions/
    ├── OniBridge-Geyser.jar
    └── onibridge-geyser/
        └── config.properties
```

The Geyser server must inherit `ONIBRIDGE_JAVA_SECRET`. Bind the Geyser Bedrock listener to the private allocation and restrict it to OniLink.

## Determining `trusted_proxy_cidrs`

Pterodactyl/Wings may present traffic from a Docker bridge, node address, NAT gateway, or proxy-container address. Determine what the backend actually observes. Start with the narrowest stable address:

- One IPv4 source: `/32`
- One IPv6 source: `/128`
- Stable dedicated container subnet: the smallest exact subnet only when individual addresses cannot be stable

Do not copy a public player CIDR or trust the whole provider/node network.

## Startup order

1. Start the BDS or Geyser backend.
2. Confirm the validator loads and its configuration succeeds.
3. For native BDS, confirm the exact hook is active.
4. Start OniLink.
5. Test a client through the proxy allocation.
6. Verify the backend allocation rejects/directly blocks an unproxied client.

A backend that shuts down on a profile failure must remain offline until the exact incompatibility is corrected.

## Persistence and backups

Persist OniLink configuration/cache/logs and all backend world/plugin data. Do not persist temporary BDS analysis caches into runtime release storage. Back up both configuration sides together so key IDs and bridge/backend names remain aligned.

## EULA and candidate status

Do not put `MINECRAFT_EULA_ACCEPTED` in a public egg or repository. It is needed only in controlled BDS acquisition/profile tooling, not normal forwarding runtime.

The current `1.26.44.3` native artifacts are acceptance-test candidates. `allow_unreviewed_profile=true` is not a production setting; complete the live checklist and promote the exact profile before production deployment.
