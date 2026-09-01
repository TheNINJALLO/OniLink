<p align="center">
  <img src="https://raw.githubusercontent.com/TheNINJALLO/OniLink/main/docs/assets/banner.svg" width="100%" alt="OniLink standalone Bedrock edge system">
</p>

<p align="center">
  <a href="https://github.com/TheNINJALLO/OniLink/actions/workflows/linux-artifacts.yml"><img alt="Linux build" src="https://img.shields.io/github/actions/workflow/status/TheNINJALLO/OniLink/linux-artifacts.yml?branch=main&amp;style=for-the-badge&amp;logo=githubactions&amp;label=Linux%20Build"></a>
  <a href="https://github.com/TheNINJALLO/OniLink/releases"><img alt="Stable release" src="https://img.shields.io/github/v/release/TheNINJALLO/OniLink?style=for-the-badge&amp;label=Release"></a>
</p>

# OniLink Wiki

OniLink is a standalone Bedrock edge system. The supported runtime family has two components:

- **OniLink** authenticates public Xbox clients, routes sessions, and runs the control plane.
- **OniBridge** restores the verified XUID before BDS selects native player storage.

> [!IMPORTANT]
> `v0.2.0` is the current stable release. The exact Linux BDS `1.26.44.3` + Endstone `0.11.9`
> profile is production-approved and remains fail closed.

## Start by goal

| Goal | Page |
| --- | --- |
| Install the first server | [[Getting Started]] |
| Follow every installation step | [[Installation Guide]] |
| Configure OniLink and OniBridge | [[Configuration]] |
| Add another BDS server | [[Adding Backends]] |
| Test the native backend | [[Native BDS Setup]] |
| Deploy in Pterodactyl | [[Pterodactyl Setup]] |
| Operate the dashboard | [[Operations Dashboard|Dashboard]] |
| Inspect cross-version packets | [[Packet Monitor]] |
| Test a 1.26.50 client on a 1.26.45 backend | [[Cross-Version-1.26.50]] |
| Configure typed player and backend actions | [[OniControl]] |
| Understand the trust model | [[Architecture and Security]] |
| Check compatibility gates | [[Compatibility and Testing]] |
| Resolve failures | [[Troubleshooting]] |
| Build a release | [[Building and Releasing]] |

## Current release

| Item | Value |
| --- | --- |
| Application | [`v0.2.0`](https://github.com/TheNINJALLO/OniLink/releases/tag/v0.2.0) (stable) |
| Preview | `v0.3.0-beta.6` preparation |
| BDS | `1.26.44.3` stable profile; `1.26.45.1` candidate profiles |
| Endstone | `0.11.9` stable profile; `0.11.10` candidate profiles |
| Linux profile | 1.26.44.3 production-approved; 1.26.45.1 candidate |
| Windows profile | Candidate |

The repository [documentation hub](https://github.com/TheNINJALLO/OniLink/blob/main/docs/README.md)
is canonical for engineering detail. This Wiki focuses on operator tasks.
