<p align="center">
  <img src="../docs/assets/banner.svg" width="100%" alt="OniLink and OniBridge">
</p>

<p align="center">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white">
  <img alt="Geyser 2.11" src="https://img.shields.io/badge/Geyser-2.11-63b8ff?style=flat-square">
  <img alt="Local verification" src="https://img.shields.io/badge/Verification-Local%20Only-52b7a8?style=flat-square">
</p>

# OniBridge-Geyser

OniBridge-Geyser is the Geyser extension for an OniLink backend that leads to a Minecraft Java server. It consumes the signed `OniForward` claim already embedded in OniLink's forged Bedrock login, verifies it locally, rejects direct or replayed joins, and restores the signed client address before Geyser opens the Java connection.

There is no HTTP verifier and no network call in the login check.

For the complete operator procedure, use the [Geyser integration guide](../docs/GEYSER.md). The remainder of this page documents the component build and its exact fail-closed configuration.

## Build

Java 21 is required. From the repository root:

```bash
./OniLink/gradlew -p OniBridge-Geyser clean test jar --no-daemon
```

The result is `OniBridge-Geyser/dist/OniBridge-Geyser.jar`. Compilation targets the official Geyser 2.11 extension API. Address and preserved-client-data access use a small fail-closed compatibility adapter because those two operations are not in the public API; a Geyser build that changes them will reject joins instead of bypassing verification.

## Install

1. Put `OniBridge-Geyser.jar` in Geyser's `extensions/` directory.
2. Start Geyser once to create `extensions/onibridge-geyser/config.properties`, then stop it.
3. Give this backend a unique standard-Base64 secret containing at least 32 random bytes. Configure its environment variable on both OniLink and Geyser.
4. Make `bridge_id`, `backend_name`, and `active_key_id` exactly match the corresponding OniLink backend forwarding settings.
5. Bind and firewall Geyser's Bedrock listener so only the OniLink host can reach it. Keep the extension's `trusted_proxy_cidrs` equally narrow.

The relevant Geyser settings are:

```yaml
bedrock:
  address: 127.0.0.1
  port: 19134

java:
  auth-type: floodgate

advanced:
  bedrock:
    validate-bedrock-login: false
    use-waterdogpe-forwarding: false
```

The matching OniLink backend is configured like this:

```properties
backends=default,java
backend.java.host=127.0.0.1
backend.java.port=19134
backend.java.dropSubChunkRequests=true
backend.java.forwarding.enabled=true
backend.java.forwarding.bridgeId=java-main
backend.java.forwarding.activeKeyId=key-1
backend.java.forwarding.activeSecretEnv=ONIBRIDGE_GEYSER_FORWARDING_SECRET
backend.java.forwarding.tokenLifetimeMillis=5000
```

`validate-bedrock-login: false` is safe only on this private, guarded listener. OniBridge-Geyser cancels the Java login unless the source CIDR, token signature, player name, XUID, backend context, lifetime, and replay state all pass. Real-address restoration is mandatory; compatibility failure also cancels the login.
