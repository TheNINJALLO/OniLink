<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="OniLink and OniBridge">
</p>

<p align="center">
  <a href="https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml"><img alt="Linux build" src="https://img.shields.io/github/actions/workflow/status/TheNINJALLO/OniLink/linux-artifacts.yml?branch=main&amp;style=for-the-badge&amp;logo=githubactions&amp;label=Linux%20Build"></a>
  <a href="https://github.com/TheNINJALLO/OniLink/releases"><img alt="Stable release" src="https://img.shields.io/github/v/release/TheNINJALLO/OniLink?style=for-the-badge&amp;label=Release"></a>
</p>

# OniLink Wiki

OniLink is a Java 21 Bedrock proxy with two fail-closed backend identity validators:

- **OniBridge** restores the verified Xbox XUID before BDS selects native player storage.
- **OniBridge-Geyser** verifies the same signed claim before Geyser opens a Java connection.

> [!IMPORTANT]
> `v0.1.7` is the current production release. Tenant and backend setup now separates the proxy address players join from the destination server address, with plain labels and examples, while retaining secure forwarding, authenticated XUID allowlisting, shared-container tenancy, stable updates, and the reviewed Linux BDS `1.26.44.3` + Endstone `0.11.9` profile.

## Start by goal

| Goal | Page |
| --- | --- |
| Understand prerequisites and choose a backend | [[Getting Started]] |
| Follow the complete install sequence | [[Installation Guide]] |
| Add another BDS server | [[Adding Backends]] |
| Test Linux BDS + Endstone | [[Native BDS Setup]] |
| Connect Geyser to a Java server | [[Geyser Java Setup]] |
| Deploy in Pterodactyl | [[Pterodactyl Setup]] |
| Operate OniLink in the browser | [[Operations Dashboard|Dashboard]] |
| Align IDs, keys, secrets, and CIDRs | [[Configuration]] |
| Understand the trust model | [[Architecture and Security]] |
| See exact versions and remaining gates | [[Compatibility and Testing]] |
| Resolve startup or join failures | [[Troubleshooting]] |
| Build or prepare another release | [[Building and Releasing]] |

## Current release

| Item | Value |
| --- | --- |
| Application release | [`v0.1.7`](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.1.7) |
| BDS | `1.26.44.3` |
| Endstone | `0.11.9` |
| Linux | x86-64, System V AMD64, libc++ |
| Geyser target | `2.11` |
| Linux native profile production-ready | Yes |

The repository [documentation hub](https://github.com/TheNINJALLO/OniLink/blob/main/docs/README.md) is canonical for engineering detail. This Wiki focuses on operator tasks.
