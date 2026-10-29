# Repository instructions

Work only within this independent Git repository. No parent repository, submodules, shared persistence models, cross-service SQL or multi-service Maven parent.

Current scope: Milestone 0 foundation only. Do not add business APIs, GIS functionality, AI models or Unreal gameplay/assets without a new milestone request.

Ownership: Rubber tapping, latex collection, DRC, production calculations, inspections and crop-health incidents.

Use ports/adapters and service-owned migrations. APIs and events are designed in agrosphere-contracts; coordinate breaking changes through a versioned contract release, never a source path dependency. UUID estateId/blockId remain stable across clients. Mark data REAL, SIMULATED, ESTIMATED or PREDICTED. GeoJSON is WGS84 longitude/latitude (RFC 7946). Procedural trees are illustrative.

Never commit credentials, local environment files, generated datasets or large geospatial assets. Use environment examples containing placeholders only. No remote creation, push or cloud deployment is authorized.

Run repository checks documented in README.md. Report actual checks separately from unavailable checks. Version independently using SemVer and CHANGELOG.md; changes to 0.x contracts can still break consumers and require review.

## Owner-authorized Milestone 3B

The project owner accepted D01–D12, ADR 0013 and the 3B package on 2026-10-27 and authorized T01–T09 in dependency order. This supplements the historical scope above. For this repository, pure collection domain/calculation work (T02) is authorized; preserve Java 25, Boot 4.0.9, JUnit 5 and existing 3A behavior. Follow the Architecture approval record/checkpoint and independent contract review/release gates. Production deployment, destructive migrations, secret-handling changes and AEO changes remain unauthorized.

Local contract release 0.5.0 and Operations artifact pinning were explicitly accepted on 2026-10-27. T03/T04 implementation is authorized in dependency order; secret-handling and production/AEO changes remain excluded.

## Scoped credential-wiring authorization - 2026-10-27

The owner authorizes the reviewed Operations-only runtime/Flyway wiring, tracked placeholders and provisioning-tool implementation, and generated credentials only in disposable tests. This supplements earlier secret-handling exclusions only for that scope. Do not read/modify existing private environment files, provision existing/running stacks, or change production/AEO state. See Architecture docs/milestones/MILESTONE_3B_CREDENTIAL_WIRING_APPROVAL.json and continue authorized T03-T09 in dependency order.

## Owner-approved WA-1 — 2026-10-29

The owner accepted D01–D10 and authorized WA-T01–T09 in dependency order; see Architecture docs/milestones/WORKER_ATTENDANCE_APPROVAL.md and ADR 0014. Pure workforce domain/ports are authorized independently of executable contract release. Keep new registration UUIDs distinct from existing workerId/sub and preserve tapping/collection contracts/history. Persistence follows stable reviewed schemas; HTTP/events/Web pinning follow independent accepted Contracts release. No REAL mutations before implemented D09 privacy gate; no workload acceptance without D08 inputs. Existing-stack/private-environment/production/AEO and Git exclusions persist.
