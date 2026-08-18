# Geyser Java Setup

Use `OniBridge-Geyser.jar` when an OniLink backend points to Geyser and then to a Java server. Do not use the native BDS plugin on this path.

## Install

1. Copy `OniBridge-Geyser.jar` into Geyser's `extensions/` directory.
2. Start Geyser once, stop it, and open `extensions/onibridge-geyser/config.properties`.
3. Configure a backend name, bridge ID, active key ID, secret environment variable, and the exact OniLink source CIDR.
4. Bind/firewall Geyser's Bedrock listener so only OniLink can reach it.

Recommended Geyser settings:

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

`validate-bedrock-login: false` is safe only on this private guarded listener with OniBridge-Geyser active.

## OniLink backend

```properties
backend.java.host=10.0.0.30
backend.java.port=19134
backend.java.dropSubChunkRequests=true
backend.java.forwarding.enabled=true
backend.java.forwarding.bridgeId=java-main
backend.java.forwarding.activeKeyId=key-1
backend.java.forwarding.activeSecretEnv=ONIBRIDGE_GEYSER_FORWARDING_SECRET
backend.java.forwarding.tokenLifetimeMillis=5000
```

Test direct-listener rejection, signed real-address restoration, reconnect, Floodgate identity, and switches in both directions. See the full [Geyser guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/GEYSER.md).
