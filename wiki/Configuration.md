# Configuration

## Matching values

| Meaning | OniLink | Native OniBridge / Geyser |
| --- | --- | --- |
| Backend | backend list name | `backend_name` |
| Bridge | `backend.<name>.forwarding.bridgeId` | `bridge_id` |
| Key ID | `backend.<name>.forwarding.activeKeyId` | `active_key_id` |
| Secret | `backend.<name>.forwarding.activeSecretEnv` | `active_secret_env` |

## Security defaults

- Use a different 32-byte-or-stronger Base64 secret for each backend.
- Keep the default 5-second token lifetime; never exceed 10 seconds.
- Keep clocks synchronized.
- Restrict `trusted_proxy_cidrs` to the exact proxy source address.
- Keep backend listeners private.
- Keep direct-join rejection and post-login XUID verification enabled.
- Keep unknown BDS/Endstone bypasses disabled.

## Rotation

1. Generate a new secret and key ID.
2. Put the current key into the validator's single previous-key slot.
3. Activate the new key on validator and proxy.
4. Wait longer than token lifetime plus skew.
5. Remove the previous key.

Never publish a secret, a complete token, a production address, or a player identifier.

The canonical reference is [docs/CONFIGURATION.md](https://github.com/TheNINJALLO/OniLink/blob/main/docs/CONFIGURATION.md). The complete OniLink option template is [onilink.example.properties](https://github.com/TheNINJALLO/OniLink/blob/main/OniLink/onilink.example.properties).
