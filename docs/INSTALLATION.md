# Installation

The current native artifacts are acceptance-test candidates, not production-approved releases. Match the compatibility-manifest row to the exact BDS executable SHA-256, operating system, architecture, and Endstone 0.11.9 before installing.

1. Put the matching `onibridge.so` or `onibridge.dll` in the backend `plugins` directory.
2. Copy `OniBridge/onibridge.example.toml` to the plugin data directory as `onibridge.toml`.
3. Set `required_profile` to the exact platform profile ID. Candidate testing additionally requires `allow_unreviewed_profile=true`; never treat that switch as production approval.
4. Set a unique backend secret as standard Base64 for at least 32 random bytes. Use the same key ID/environment variable in OniBridge and that OniLink backend.
5. Firewall the BDS UDP listener to the proxy and configure exact `trusted_proxy_cidrs`.
6. Start BDS and confirm the exact hook is active. Any shutdown is a compatibility/security failure.
7. Start OniLink with `java -jar OniLink.jar onilink.properties` (or omit the path to use `config.properties`).
8. Complete the live checklist in `TESTING.md` before removing the candidate designation.

Do not install a patched Endstone/Onistone runtime or Python authentication plugin. BDS server archives and executables are never copied from this repository's `dist` directory.

For a Java backend behind Geyser, install `OniBridge-Geyser.jar` in Geyser's `extensions` directory instead of the native BDS plugin. Configure the same backend-specific OniForward key ID, secret, bridge ID, and backend name on OniLink and OniBridge-Geyser; bind/firewall the Geyser Bedrock listener to the proxy; and set `backend.<name>.dropSubChunkRequests=true` in OniLink. The complete fail-closed Geyser configuration is documented in [OniBridge-Geyser](../OniBridge-Geyser/README.md).
