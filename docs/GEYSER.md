# Geyser and Java backend integration

Use this path when an OniLink backend points to Geyser and then to a Minecraft Java server. Install `OniBridge-Geyser.jar`; do not install the native OniBridge `.so` on the Java server.

## Trust boundary

The Geyser Bedrock listener becomes a private backend listener. It must be bound or firewalled so only OniLink can reach it. `OniBridge-Geyser` rejects a join unless the socket source and the signed `OniForward` claim both pass.

There is no HTTP verifier and no network call during claim validation.

## Install the extension

1. Copy `OniBridge-Geyser.jar` into Geyser's `extensions/` directory.
2. Start Geyser once, then stop it.
3. Open `extensions/onibridge-geyser/config.properties`.
4. Give this backend its own standard-Base64 secret containing at least 32 random bytes.
5. Make the secret available to both Geyser and OniLink as `ONIBRIDGE_GEYSER_FORWARDING_SECRET`.

Example extension configuration:

```properties
bridge_id=java-main
backend_name=java
trusted_proxy_cidrs=10.0.0.10/32

active_key_id=key-1
active_secret_env=ONIBRIDGE_GEYSER_FORWARDING_SECRET
active_secret_file=

previous_key_id=
previous_secret_env=
previous_secret_file=

maximum_token_size=4096
maximum_lifetime_millis=10000
allowed_clock_skew_millis=2000
replay_cache_maximum_entries=10000
```

Replace `10.0.0.10/32` with the exact OniLink source address.

## Configure Geyser

Use a private listener and Floodgate authentication:

```yaml
bedrock:
  address: 10.0.0.30
  port: 19134

java:
  auth-type: floodgate

advanced:
  bedrock:
    validate-bedrock-login: false
    use-waterdogpe-forwarding: false
```

> [!WARNING]
> `validate-bedrock-login: false` is safe only on this private guarded listener. OniLink performs public Xbox authentication, and OniBridge-Geyser must remain installed and fail-closed. Never expose this Geyser listener directly.

## Configure OniLink

Add the Geyser backend to `config.properties`:

```properties
backends=default,java
backend.java.host=10.0.0.30
backend.java.port=19134
backend.java.dropSubChunkRequests=true

backend.java.forwarding.enabled=true
backend.java.forwarding.bridgeId=java-main
backend.java.forwarding.activeKeyId=key-1
backend.java.forwarding.activeSecretEnv=ONIBRIDGE_GEYSER_FORWARDING_SECRET
backend.java.forwarding.tokenLifetimeMillis=5000
```

`backend.java.dropSubChunkRequests=true` avoids forwarding BDS-specific terrain requests to Geyser after a backend switch.

## Validate the deployment

Test all of the following before relying on the path:

- A normal connection through OniLink reaches the Java backend.
- Direct access to the Geyser Bedrock listener is blocked at the network layer and rejected by the extension.
- Replayed, expired, tampered, wrong-backend, and wrong-bridge claims are rejected.
- The Java side observes the signed real client address.
- Reconnect and BDS-to-Geyser/Geyser-to-BDS switches behave correctly.
- Floodgate identity and permissions remain stable.

If address access changes in a future Geyser build, the compatibility adapter rejects the join instead of silently losing verification. See [Troubleshooting](TROUBLESHOOTING.md) and the component [README](../OniBridge-Geyser/README.md) for implementation details.
