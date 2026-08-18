# Deployment examples

These examples are copy/paste starting points for the current OniLink candidate. Replace every example address, hostname, backend name, and environment-variable name with values from your network.

No example contains a forwarding secret. Generate a unique secret for each backend and supply it to both OniLink and that backend through the named environment variable.

## Available layouts

| Directory | Topology |
| --- | --- |
| [`single-bds`](single-bds/) | One OniLink proxy and one Linux BDS + Endstone backend |
| [`mixed-bds-geyser`](mixed-bds-geyser/) | One OniLink proxy, one BDS backend, and one Geyser-backed Java server |

## Example address plan

| Service | Address | Exposure |
| --- | --- | --- |
| OniLink | `10.10.0.10:19132/udp` | Public/player-facing |
| Survival BDS | `10.10.0.20:19133/udp` | Private; OniLink only |
| Geyser Bedrock listener | `10.10.0.30:19134/udp` | Private; OniLink only |

The `trusted_proxy_cidrs` value is the source address the backend actually sees. In these examples that is `10.10.0.10/32`. Container networks and NAT may present a different address; verify it instead of copying this value blindly.

## Before use

1. Read [Installation](../docs/INSTALLATION.md).
2. Verify release checksums and the exact BDS executable hash.
3. Replace the example addresses and `play.example.com`.
4. Generate one secret per backend.
5. Put each secret into the matching environment variable on both processes.
6. Keep backend listeners private.
7. Validate the candidate using [Testing](../docs/TESTING.md).
