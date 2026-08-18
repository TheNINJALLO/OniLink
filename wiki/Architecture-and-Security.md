# Architecture and Security

```text
Public Bedrock client
        |
        v
OniLink: Xbox authentication + routing
        |
        +-- short-lived, backend-bound OniForward --> OniBridge --> BDS storage
        |
        +-- short-lived, backend-bound OniForward --> Geyser extension --> Java
```

## Trust model

OniLink validates the Mojang/Xbox identity chain and observes the real socket address. Before every backend connection it creates a fresh HMAC-signed claim containing the authenticated identity, target context, session, nonce, timestamps, and address.

The backend validator checks:

- actual proxy source CIDR;
- signature and key ID;
- backend and bridge binding;
- player name and XUID binding;
- issue/expiry time and maximum lifetime;
- session/nonce replay state;
- bounded token and field formats.

Native OniBridge applies the verified XUID before BDS selects player storage and checks it again after player construction. OniBridge-Geyser restores the signed real address before Geyser connects to Java.

## Fail-closed behavior

Missing claims, unknown keys, wrong context, bad signatures, expired/future tokens, replays, untrusted sources, unknown binaries, mismatched hook bytes, incompatible Geyser access, and post-login identity mismatches reject the join or shut down the unsafe backend path.

Read the canonical [architecture](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ARCHITECTURE.md), [identity flow](https://github.com/TheNINJALLO/OniLink/blob/main/docs/IDENTITY_FLOW.md), and [OniForward protocol](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ONIFORWARD_PROTOCOL.md).
