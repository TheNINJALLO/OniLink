# Configuration

Start with the [single-BDS example](https://github.com/TheNINJALLO/OniLink/tree/main/examples/single-bds)
and replace its addresses, names, and secret source. The
[complete option reference](https://github.com/TheNINJALLO/OniLink/blob/main/docs/CONFIGURATION.md)
documents every setting.

## Matching values

| Meaning | OniLink | OniBridge |
| --- | --- | --- |
| Backend | name in `backends` | `backend_name` |
| Bridge | `backend.<name>.forwarding.bridgeId` | `bridge_id` |
| Key ID | `backend.<name>.forwarding.activeKeyId` | `forwarding.active_key_id` |
| Secret | `backend.<name>.forwarding.activeSecretEnv` | `forwarding.active_secret_env` |

Example proxy route:

```properties
backends=survival
backend.survival.host=10.10.0.20
backend.survival.port=19133
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET
```

Matching native values:

```toml
bridge_id = "survival-main"
backend_name = "survival"
trusted_proxy_cidrs = ["10.10.0.10/32"]

[forwarding]
active_key_id = "key-2026-01"
active_secret_env = "ONIBRIDGE_SURVIVAL_SECRET"
active_secret_file = ""
```

The fields contain the environment variable's name, not the Base64 secret. Supply identical secret
bytes to both processes through their environment. Generate the value with:

```bash
openssl rand -base64 32
```

Use a unique secret for every backend. Keep listeners private, trusted CIDRs narrow, direct-join
rejection enabled, and all unknown-profile/binary bypasses disabled. Java-properties comments must
be on their own lines; native TOML rejects unknown and duplicate keys.
