# OniLink System Identity

OniLink is a standalone Bedrock edge system with its own runtime, control plane, configuration,
release stream, command namespace, and documentation. It is not an edition, mode, addon, or
component of another proxy.

| Name | Role |
| --- | --- |
| **OniLink** | The complete system and its Java 21 public proxy/control-plane runtime |
| **OniForward** | The signed identity-forwarding protocol |
| **OniBridge** | The native BDS identity validator |
| **OniBridge-Geyser** | The Geyser identity validator |
| **OniLink BDS tooling** | Exact-version profile and release tooling |

Endstone, BDS, Geyser, and other dependencies remain independent integrations. Third-party names in
legal notices and internal provenance records are attribution only; they are not OniLink products or
co-branding.

See the canonical [system identity document](https://github.com/TheNINJALLO/OniLink/blob/main/docs/SYSTEM_IDENTITY.md)
for naming and ownership boundaries.
