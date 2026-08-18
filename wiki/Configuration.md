# Configuration

Start with a complete, copyable deployment and then replace its addresses, names, and secret sources:

- [One OniLink + one native BDS backend](https://github.com/TheNINJALLO/OniLink/tree/main/examples/single-bds)
- [One OniLink + native BDS + Geyser/Java](https://github.com/TheNINJALLO/OniLink/tree/main/examples/mixed-bds-geyser)
- [Complete option reference](https://github.com/TheNINJALLO/OniLink/blob/main/docs/CONFIGURATION.md)

## The four matching values

| Meaning | OniLink | Native OniBridge | OniBridge-Geyser |
| --- | --- | --- | --- |
| Backend | name in `backends` | `backend_name` | `backend_name` |
| Bridge | `backend.<name>.forwarding.bridgeId` | `bridge_id` | `bridge_id` |
| Key ID | `backend.<name>.forwarding.activeKeyId` | `forwarding.active_key_id` | `active_key_id` |
| Secret source | `backend.<name>.forwarding.activeSecretEnv` | `forwarding.active_secret_env` | `active_secret_env` |

Matching environment-variable names are not enough: both processes must receive identical decoded secret bytes. OniLink also requires a unique active secret source for every enabled backend.

## Environment-variable example

The proxy:

```properties
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-2026-01
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_SURVIVAL_SECRET
backend.survival.forwarding.tokenLifetimeMillis=5000
```

The native validator:

```toml
[forwarding]
active_key_id = "key-2026-01"
active_secret_env = "ONIBRIDGE_SURVIVAL_SECRET"
active_secret_file = ""
```

Provide the value to both services without putting it in either file:

```bash
export ONIBRIDGE_SURVIVAL_SECRET='REPLACE_WITH_A_BASE64_32_BYTE_SECRET'
```

Use standard Base64 representing at least 32 bytes. Generate it with `openssl rand -base64 32`.

## Secret-file example

For a restricted Linux file:

```bash
sudo install -d -m 0700 -o onilink -g onilink /etc/onilink/secrets
sudo install -m 0600 -o onilink -g onilink survival.secret /etc/onilink/secrets/survival.secret
```

On OniLink, replace `activeSecretEnv` with:

```properties
backend.survival.forwarding.activeSecretFile=/etc/onilink/secrets/survival.secret
```

On the validator, clear the environment source and set its file source. Configure exactly one source, never both. If OniLink and the backend run on separate hosts or containers, create an independently protected file containing the same secret on each side; do not expose a shared world-readable volume.

## Listener and routing example

```properties
listener.host=0.0.0.0
listener.port=19132
publicAddress=play.example.com:19132

backends=survival,java
hubBackend=survival
backend.survival.host=10.10.0.20
backend.survival.port=19133
backend.java.host=10.10.0.30
backend.java.port=19134
backend.java.dropSubChunkRequests=true

join.try=survival,java
join.attemptsPerBackend=2
failover.enabled=true
failover.fallbacks=survival,java
backend.survival.fallback=java
backend.java.fallback=survival
```

The public DNS and port point to OniLink. Backend addresses are the private addresses reachable from the proxy.

## Parsing rules and safe defaults

- Put Java-properties comments on their own lines. In `key=value # comment`, the `# comment` text becomes part of the value.
- Native TOML rejects unknown and duplicate keys. OniBridge-Geyser rejects unknown keys; avoid duplicate Java-properties keys because the last value wins.
- Use a different secret for each backend.
- Keep the proxy token lifetime at `5000` ms and validator maximum at `10000` ms or less.
- Keep clocks synchronized and the default clock-skew allowance narrow.
- Restrict trusted proxy CIDRs to the exact observed source, normally `/32` or `/128`.
- Keep backend listeners private and direct-join rejection enabled.
- Leave post-login XUID verification enabled.
- Leave unknown BDS, unknown Endstone, and hook-failure bypasses disabled.

## Rotate a key

1. Generate a new secret and choose a new key ID.
2. On the validator, make the new key active and put the current key in its single previous-key slot.
3. Restart/reload the validator first; it now accepts both keys.
4. Change OniLink's active key to the new key, restart it, and test a join. OniLink signs only with its active key.
5. Wait longer than the token lifetime plus clock skew.
6. Clear the validator's previous-key fields and remove the old secret.

Never publish a secret, complete forwarding token, production address, or player identifier.
