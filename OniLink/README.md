# OniLink

OniLink is a Java 21 Bedrock proxy with Xbox client authentication, backend discovery/routing/failover/switching, adjacent protocol translation, packet relay, resource-pack caching, registry continuity, permissions, addons, and connection controls. Its primary proxy namespace is `/onilink`; unrelated backend and Endstone plugin commands remain backend-authoritative.

Each protected backend needs a unique OniForward secret supplied through an environment variable or restricted file. See `onilink.example.properties` and [CONFIGURATION.md](../docs/CONFIGURATION.md).

Build with `gradlew test standaloneJar`. The expected artifact is `dist/OniLink.jar`.

