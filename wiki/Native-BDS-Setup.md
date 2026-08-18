# Native BDS Setup

This page covers the Linux `v0.1.0-candidate.1` acceptance build.

> [!WARNING]
> Use only BDS `1.26.44.3` Linux x86-64 with Endstone `0.11.9`. A nearby version is not compatible.

## Install

1. Verify the BDS executable SHA-256 is `06effdd00067f1ae0951ee7a732398dde721728e6b18ea149b138b8e2aececa7`.
2. Stop BDS.
3. Copy `onibridge-0.1.0-bds-1.26.44.3-linux-x86_64.so` into `plugins/`.
4. Start BDS once. The plugin creates `plugins/onibridge/onibridge.toml` and intentionally shuts down.
5. Configure the generated file with:
   - a stable `bridge_id`;
   - the OniLink backend name;
   - the exact proxy source CIDR;
   - matching key ID and secret environment variable;
   - profile `bds-1.26.44.3-linux-x86_64-06effdd00067f1ae`.
6. Set `allow_unreviewed_profile=true` only for this controlled candidate test.
7. Start BDS with the secret present in its environment.

Keep all other compatibility bypasses `false`. A critical message or automatic shutdown is a failed compatibility/security test.

## Configure OniLink

```properties
backend.name=survival
backends=survival
backend.survival.host=10.0.0.20
backend.survival.port=19133

forwarding.proxyId=edge-1
backend.survival.forwarding.enabled=true
backend.survival.forwarding.bridgeId=survival-main
backend.survival.forwarding.activeKeyId=key-1
backend.survival.forwarding.activeSecretEnv=ONIBRIDGE_FORWARDING_SECRET
backend.survival.forwarding.tokenLifetimeMillis=5000
```

Start BDS first, then run:

```bash
java -jar OniLink.jar config.properties
```

## Acceptance

Test valid proxy joins, direct-join rejection, tampering, expiry, replay, concurrency, restart/rejoin inventory continuity, Ender Chest, permissions, bans, allowlists, commands, and real client address behavior.

The full procedure is in the repository's [quick start](https://github.com/TheNINJALLO/OniLink/blob/main/docs/QUICKSTART.md) and [testing guide](https://github.com/TheNINJALLO/OniLink/blob/main/docs/TESTING.md).
