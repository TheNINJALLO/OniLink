# Geyser Java Setup

Use `OniBridge-Geyser.jar` when an OniLink backend points to Geyser and then to a Java server. Do not install the native BDS plugin on this path.

## Example network

| Item | Example value |
| --- | --- |
| Public OniLink listener | `10.10.0.10:19132/udp` |
| Private Geyser Bedrock listener | `10.10.0.30:19134/udp` |
| Backend name | `java` |
| Bridge ID | `java-main` |
| Active key ID | `key-2026-01` |
| Secret variable | `ONIBRIDGE_JAVA_SECRET` |

Back up the Geyser and Floodgate configuration first. OniLink is the only public Bedrock listener; the Geyser Bedrock listener must be private or firewalled to OniLink.

## 1. Install the extension

1. Stop Geyser.
2. Copy `OniBridge-Geyser.jar` into Geyser's `extensions/` directory.
3. Start Geyser once, then stop it.
4. Open `extensions/onibridge-geyser/config.properties`.

## 2. Create the secret

Generate a unique secret for this backend:

```bash
openssl rand -base64 32
export ONIBRIDGE_JAVA_SECRET='REPLACE_WITH_THE_GENERATED_VALUE'
```

The Geyser process and OniLink process must receive the same value. Do not reuse a native BDS backend's secret.

## 3. Configure OniBridge-Geyser

```properties
bridge_id=java-main
backend_name=java
trusted_proxy_cidrs=10.10.0.10/32

active_key_id=key-2026-01
active_secret_env=ONIBRIDGE_JAVA_SECRET
active_secret_file=

previous_key_id=
previous_secret_env=
previous_secret_file=

maximum_token_size=4096
maximum_lifetime_millis=10000
allowed_clock_skew_millis=2000
replay_cache_maximum_entries=10000
```

Set `trusted_proxy_cidrs` to the source address Geyser actually observes. The extension rejects unknown keys and requires exactly one active secret source. On POSIX systems, a configured secret file must not be accessible to other users.

## 4. Configure Geyser

Merge the relevant values into Geyser's existing configuration; do not replace unrelated Java-server connection settings:

```yaml
bedrock:
  address: 10.10.0.30
  port: 19134
java:
  auth-type: floodgate
advanced:
  bedrock:
    validate-bedrock-login: false
    use-waterdogpe-forwarding: false
```

`validate-bedrock-login: false` is safe only when this listener is private, only OniLink can reach it, and OniBridge-Geyser is active. If the extension fails to load, keep the listener closed.

## 5. Configure the OniLink backend

Add `java` to the `backends` list and configure the matching block:

```properties
backends=survival,java
backend.java.host=10.10.0.30
backend.java.port=19134
backend.java.dropSubChunkRequests=true
backend.java.forwarding.enabled=true
backend.java.forwarding.bridgeId=java-main
backend.java.forwarding.activeKeyId=key-2026-01
backend.java.forwarding.activeSecretEnv=ONIBRIDGE_JAVA_SECRET
backend.java.forwarding.tokenLifetimeMillis=5000
```

`backend.java.dropSubChunkRequests=true` is required for the Geyser route. A ready-to-copy mixed network is in [`examples/mixed-bds-geyser`](https://github.com/TheNINJALLO/OniLink/tree/main/examples/mixed-bds-geyser).

## 6. Start and verify

Start the Java server, Geyser with the secret present, and then OniLink. Confirm the extension reports an active validator before opening the public listener.

Test:

- Direct access to the private Geyser listener is rejected or blocked.
- A connection through OniLink receives the expected Floodgate identity and real client address.
- Reconnects and backend switches work in both directions.
- An expired, replayed, or altered forwarding claim is rejected.
- Geyser refuses to start the route safely if the secret or extension configuration is invalid.

See the full [Geyser guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/GEYSER.md), [installation guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/INSTALLATION.md), and [troubleshooting guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TROUBLESHOOTING.md).
