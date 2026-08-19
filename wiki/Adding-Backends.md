<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="Add BDS backends to OniLink">
</p>

# Adding Backends

The OniLink dashboard can add a BDS + Endstone route, generate a unique secret, and create one
matched setup ZIP without hand-editing both sides.

> [!IMPORTANT]
> Use a unique backend name, bridge ID, and secret for every server. The wizard is for native BDS; follow [[Geyser Java Setup]] for a Java backend.

## Dashboard setup

1. Prepare the new BDS `1.26.44.3` + Endstone `0.11.9` server and install the current OniBridge `.so` in `plugins/`.
2. Sign in to OniLink as an admin or owner.
3. Open **Add Backend** in the main navigation. The same wizard is linked from **Backends** and **Configuration**.
4. Enter:

   | Field | Example |
   | --- | --- |
   | Backend name | `creative` |
   | BDS allocation | `198.51.100.20:25571` |
   | OniLink public IP | `198.51.100.10` |

5. Select **Create backend setup package** and immediately download
   `creative-onibridge-setup.zip`.
6. Extract the ZIP, stop the new BDS server, and upload its key and TOML here:

   ```text
   /home/container/plugins/onibridge/
   ├── creative.key
   └── onibridge.toml
   ```

7. Start BDS and confirm `OniBridge native identity hook is active for the exact reviewed profile.`
8. Restart OniLink.
9. Join through OniLink and run `/server creative`.

OniLink has already created its own `secrets/creative.key` and updated `config.properties`; the result page shows the exact non-secret properties for reference. Do not copy the secret text into `active_secret_env`; the wizard uses `active_secret_file` on both sides. The current Linux plugin automatically restricts an uploaded key to owner-only access.

Adding the backend does not change the hub. Use the raw configuration editor only when you deliberately want to update `hubBackend`, `join.try`, or failover settings.

OniLink itself needs one primary allocation. Bedrock uses UDP and the dashboard uses TCP on that same
number. Every backend keeps its own BDS UDP allocation; adding a backend does not require another
OniLink port.

## Manual fallback

Without the dashboard, add `creative` to `backends=`, create a unique `secrets/creative.key`, and add:

```properties
backend.creative.host=198.51.100.20
backend.creative.port=25571
backend.creative.forwarding.enabled=true
backend.creative.forwarding.bridgeId=creative-main
backend.creative.forwarding.activeKeyId=key-1
backend.creative.forwarding.activeSecretEnv=
backend.creative.forwarding.activeSecretFile=secrets/creative.key
backend.creative.forwarding.tokenLifetimeMillis=5000
```

Put the identical secret in the Endstone plugin directory and use a complete TOML with:

```toml
bridge_id = "creative-main"
backend_name = "creative"
trusted_proxy_cidrs = ["198.51.100.10/32"]

[forwarding]
active_key_id = "key-1"
active_secret_env = ""
active_secret_file = "creative.key"

[compatibility]
required_profile = "bds-1.26.44.3-linux-x86_64-06effdd00067f1ae"
allow_unreviewed_profile = false
```

Restart the backend first, then OniLink. The full explanation, routing examples, removal steps, and failure table are in the repository's [Adding another BDS backend guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ADDING_BACKEND.md).
