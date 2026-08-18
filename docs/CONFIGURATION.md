# Configuration

OniLink uses properties; OniBridge uses strict TOML. Unknown OniBridge keys are rejected. The examples are `OniLink/onilink.example.properties` and `OniBridge/onibridge.example.toml`.

For every backend, align `backend.<name>.forwarding.bridgeId`, `activeKeyId`, and the secret source with OniBridge's `bridge_id`, `forwarding.active_key_id`, and secret source. Use `forwarding.proxyId` to identify the proxy instance. The token lifetime defaults to 5 seconds and may not exceed 10 seconds.

Secrets are standard Base64 for at least 32 random bytes. Environment variables are suitable for Pterodactyl; restricted files are available for conventional hosts. Never put a real secret in version control. During rotation, configure the new active key on both sides and the old key as OniBridge's optional previous key; remove it after the maximum lifetime plus skew.

`identity.uuid_mode="preserve_backend"`, direct-join rejection, post-login XUID verification, unknown-runtime rejection, and shutdown-on-hook-failure are production defaults. Experimental proxy UUID mode can split UUID-indexed data and is not validated.

