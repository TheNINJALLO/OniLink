# OniLink system identity

OniLink is a standalone Bedrock edge system. It has its own runtime, browser control plane,
configuration format, update channel, release lifecycle, command namespace, and documentation. Do
not present OniLink as an edition, mode, addon, continuation, or component of another proxy product.

## Official system family

| Official name | What it identifies | Distribution |
| --- | --- | --- |
| **OniLink** | The complete system and its Java 21 public proxy/control-plane runtime | `OniLink.jar` |
| **OniForward** | OniLink's signed, backend-bound identity-forwarding protocol | Built into the proxy and validators |
| **OniBridge** | The native BDS identity validator in the OniLink system | `onibridge.so` or `onibridge.dll` |
| **OniLink BDS tooling** | Exact-version acquisition, inspection, profile, and packaging tools | Repository build tools only |

Use **OniLink** for the overall product and **OniLink system** when the distinction between the
whole platform and `OniLink.jar` matters. Use the exact component names above for backend-specific
instructions.

## Ownership boundaries

- Endstone, BDS, Cloudburst protocol code, and other dependencies keep their own names and
  licenses. They are integrations or dependencies, not members of the OniLink product family.
- A tenant proxy is an isolated OniLink listener managed by the same control plane. It is not a new
  product or a separately installed panel.
- Third-party names retained in `NOTICE` and the internal source audit exist only for license and
  engineering provenance. They do not imply branding, sponsorship, affiliation, or product
  membership.

## Public presentation rules

- Release titles, artifacts, panel eggs, screenshots, and dashboard copy use OniLink branding only.
- The repository banner identifies OniLink as a **standalone Bedrock edge system**.
- Public setup guides describe capabilities as OniLink behavior, not as renamed behavior from a
  different product.
- New components use the `OniLink`, `OniBridge`, or `OniForward` family name only when they are
  maintained and released as part of this repository.

For the runtime topology, continue with [Architecture](ARCHITECTURE.md). For third-party license and
source records, see [NOTICE](../NOTICE) and the internal [source audit](SOURCE_AUDIT.md).
