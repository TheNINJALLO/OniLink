# Pterodactyl

Use separate containers or allocations for OniLink and each BDS backend. Keep backend UDP allocations private or firewall them to the proxy container network. Add `ONIBRIDGE_FORWARDING_SECRET` as a masked environment variable containing Base64 for at least 32 random bytes; use a different value per backend and align key/bridge/backend IDs in both configurations.

Do not put `MINECRAFT_EULA_ACCEPTED` in a public egg or repository. It is needed only in a controlled compatibility-build environment, not normal runtime. Persist OniLink configuration/caches and the BDS world/plugin data, but do not persist temporary BDS analysis caches into release storage.

Startup order is backend first, with OniBridge confirming an active exact profile, then OniLink. A backend that shuts down on a profile failure must remain offline until a reviewed compatible artifact is installed.

The current 1.26.44.3 artifacts are acceptance-test candidates. Do not enable `allow_unreviewed_profile` on a production panel; complete the platform live checklist and promote the exact profile first.
