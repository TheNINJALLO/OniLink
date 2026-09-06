# Expansion implementation

The native backend continues to require Endstone. Protocol fixture success does not establish
native binary compatibility or replace a real client/server acceptance run.

Implementation checklist (updated as each workflow is verified):

- [x] Confirmed evacuation and durable return reservations
- [x] Update Center: bounded uploads, archive verification, candidate review and deployment history
- [x] Compatibility lab: executable packet fixtures and explicit live acceptance evidence
- [x] Signed protocol packages: trust, dependencies, session snapshots and rollback
- [x] Managed maintenance: backup, file installation, process health and confirmed return
- [x] Player services: parties, friends, chat, server selection, queues and reserved slots
- [x] Resource-pack releases: dependency checks, staged activation and rollback
- [x] Addon SDK: versioned contract, lifecycle isolation, generator and contract checks
- [x] Multiple nodes: authenticated shared presence, moderation, routing and capacity leases
- [x] OniPulse operational metrics and OTLP export

Local verification passed 520 Java tests, 41 dashboard tests, 88 Python tests and two Windows native
tests, plus the generated addon lifecycle contract. Three Python checks have expected Windows skips.
The standalone JAR, frontend checks and archive inspection for both supplied 1.26.45.1 platforms
passed. Maintenance fixtures also cover conflicting rollback rejection and manually repaired job
recovery through confirmed player returns. See [Testing](TESTING.md#expansion-verification-on-2026-09-06).

See [Update Center](UPDATE_CENTER.md) and [Protocol packages](PROTOCOL_PACKAGES.md) for the operator
workflow, configuration, limits and distinctions between fixture results and live acceptance.

Live deployment requires configured Endstone commands and server directories. No production
server or client connection has been supplied in this workspace.
