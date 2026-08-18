# OniLink security

Follow the repository [security policy](../SECURITY.md). Keep each backend listener firewalled to its proxy, require OniBridge's exact reviewed compatibility profile, and configure a unique OniForward secret per backend through an environment variable or restricted file.

OniLink accepts public client connections, so its Xbox chain validation, XUID requirement, RakNet/session limits, batch bounds, and command authorization must remain enabled. Never log a secret or complete OniForward token.

