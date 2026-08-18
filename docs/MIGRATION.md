# Migration

Back up the world, `allowlist.json`, `permissions.json`, plugin data, and proxy configuration. Install OniLink and OniBridge side-by-side with the former deployment but do not expose the backend publicly. Translate backend names and routing first, then configure a fresh unique OniForward key for each backend.

Keep `uuid_mode="preserve_backend"`. The verified XUID restores BDS's XUID-keyed identity while the existing backend UUID avoids moving permissions, scoreboards, Script API identities, or plugin databases. Any experimental UUID migration requires an offline, explicit mapping and independent backups.

Legacy verifier identity/operator records may be read only by an explicit one-way migration operation. They never authenticate a current join. Run a dry-run plan, ensure the destination does not exist, require confirmation, and retain the backup until two successful join/rejoin cycles and permission checks complete.

