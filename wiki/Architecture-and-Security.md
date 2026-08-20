# Architecture and Security

```text
Public Bedrock client
        |
        v
OniLink: Xbox authentication + routing
        |
        | short-lived, backend-bound OniForward
        v
OniBridge: pre-storage identity verification
        |
        v
BDS native player storage
```

OniLink validates the Mojang/Xbox identity chain and observes the real socket address. Before every
backend connection it creates a fresh HMAC-signed claim containing authenticated identity, target
context, session, nonce, timestamps, and address.

OniBridge checks the actual proxy source CIDR, signature/key ID, backend/bridge binding, player
name/XUID binding, timestamps, replay state, and bounded field formats. It applies the verified XUID
before BDS selects storage and checks it again after player construction.

Missing claims, unknown keys, wrong context, bad signatures, expiry, replay, untrusted sources,
unknown binaries, mismatched hook bytes, and post-login identity mismatches reject the join or shut
down the unsafe backend path.

Read the canonical [architecture](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ARCHITECTURE.md),
[identity flow](https://github.com/TheNINJALLO/OniLink/blob/main/docs/IDENTITY_FLOW.md), and
[OniForward protocol](https://github.com/TheNINJALLO/OniLink/blob/main/docs/ONIFORWARD_PROTOCOL.md).
