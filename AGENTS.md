# Repository instructions

## II-1 executable accepted and implemented locally - 2026-10-31

Architecture INCIDENT_INSPECTION_CONTRACT_APPROVAL.md records exact local Contracts 0.7.0 release/pinning acceptance. II-T03 onward is authorized in dependency order; local functional implementation/checks are recorded in docs/CROP_HEALTH_IMPLEMENTATION.md and Architecture INCIDENT_INSPECTION_VERIFICATION.md. Earlier pending labels are historical. Existing-stack/REAL/private-environment/role/credential/production/Git/AEO exclusions and privacy/load gates persist.

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

## Owner-approved II-1 — 2026-10-31

The owner accepted II-D01–10 and authorized dependency-ordered II-T01–09; see Architecture INCIDENT_INSPECTION_APPROVAL.md and ADR 0015. Pure crophealth domain/application ports may precede executable release. Preserve Java 25 / Boot 4.0.9 / JUnit 5, existing modules, V1–V4, current contract pins and local records. Persistence follows stable reviewed semantics; HTTP/producer pins require accepted independent executable release. New mutations SIMULATED-only; II-D07/08 and WA D08/D09 remain open. No running-stack/private-environment/role/credential/production/destructive migration/Git publication/AEO mutation.

## Owner-approved AN-1 — 2026-10-31

Architecture ALERTS_NOTIFICATIONS_APPROVAL.md and ADR 0016 accept dependency-ordered AN-T01–09. Pure alert rules/read/ack/authorization/application ports are authorized independently of executable release. Persistence requires stable reviewed schema; HTTP/pins require exact executable acceptance/local release. Preserve Java 25/Boot 4.0.9/JUnit 5, V1–V5, old behavior, source contracts and data. No runtime adapter/migration until its prerequisite is satisfied. AN-D07/08 and WA/II/M2 gates stay open; generated-only development, no non-generated rollout/REAL/live-stack/private configuration/role/credential/external delivery/Git/deployment/destructive/AEO action.

## AN-1 exact executable accepted — 2026-10-31

Architecture ALERTS_NOTIFICATIONS_CONTRACT_APPROVAL.md records owner acceptance of the exact four artifacts, local Contracts 0.8.0 release and Operations/Web pins. Continue accepted AN-T03–09 by dependency; preserve prior features/artifacts and reviewed bytes. Generated-only verification; AN-D07/08 and WA/II/M2 gates remain open. No non-generated account rollout, REAL, existing-stack/private configuration/role/credential/external delivery/Git/deployment/destructive/data-reset/AEO action. Gateway adds only /api/v1/alerts/**; Infrastructure generated-only drills reuse existing topology without a new alert queue.

## AN-1 local verification state — 2026-10-31

Architecture ALERTS_NOTIFICATIONS_VERIFICATION.md records local implementation and generated-only checks after exact executable acceptance. Earlier preparation labels are historical. Preserve 0.8.0 pins/old bytes/V1–V5/Web config/index and existing data/volumes. Default-disabled subject-policy gate, AN-D07/08, WA/II/M2 and no non-generated rollout/REAL/live-stack/private configuration/role/credential/Git/deployment/AEO exclusions remain.

## Owner-approved AN privacy extension — 2026-10-31

Architecture ALERTS_PRIVACY_APPROVAL.md records exact five-artifact privacy candidate acceptance (manifest ea6402a205d1da7a0801090b0acabac58ae164ba6544b474d709d8a4a608286b), local independent Contracts 0.9.0 release/archive/pins and generated-only AP-T02–07 implementation. Preserve accepted bytes, V1–V6, old events/records and all staged work/Web config. Additive privacy storage and narrowly scoped maintenance may be tested only in separately owned disposable generated databases. No existing-stack/private configuration/actual-account/REAL/existing role or credential/destructive migration/historical conversion/external delivery/Git publication/deployment/AEO action. AP-T08 local-stack execution remains separately gated; do not repeat II-1. Prior approval/pending labels remain historical within their scope; do not ask again for this exact acceptance.
