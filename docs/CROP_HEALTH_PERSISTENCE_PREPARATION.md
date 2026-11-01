# II-1 persistence preparation — 2026-10-31

Design/preparation only. II-T03 migration/JDBC implementation has not begun; no draft SQL is on the Flyway classpath. Current source head is V4__worker_registration_and_attendance.sql. V1–V4 and existing pins remain unchanged. The owner accepted II-1 design/II-T01–09; executable release/pinning remains a separate gate. See Architecture INCIDENT_INSPECTION_APPROVAL.md and the isolated Contracts docs/incident-inspection candidate.

## Source observations and proposed tables

V3/V4 source checks the existing restricted agrosphere_operations_app login and grants only explicit table operations. New tables will not automatically have runtime access. These are source observations, not an audit of live grants. Preserve separate migration/runtime connections and never provision/change roles or credentials here. Any new privilege expansion beyond the accepted task scope returns to review; no grants were executed by this increment.

Operations owns proposed additive V5; recheck the migration head before authoring. No cross-service SQL, Estate DB foreign key, spatial table, extension installation or new service is needed.

| Table | Constraints / intended runtime access |
|---|---|
| field_inspection | UUID PK, stable estate/block/provenance; observation/timezone/outcome/state; nullable unique linked incident ID; int64 version/audit and private JSON snapshot. SIMULATED-only CHECK. SELECT/INSERT/constrained UPDATE, no DELETE. CLEAR may retain only a VOIDED linked incident after a correction; FOLLOW_UP always requires a link. |
| crop_health_incident | UUID PK; estate/block/provenance; nullable unique inspection ID; report fields/status/current resolution; earliest retained resolution timestamp; version/audit/private snapshot. SIMULATED-only CHECK. SELECT/INSERT/constrained UPDATE, no DELETE. Linked identities must differ and match estate/block/provenance/timezone. |
| inspection_revision / incident_revision | Root+version PK; full immutable resulting root snapshot, kind/reason/actor/recordedAt/correlation. SELECT/INSERT only; reject UPDATE/DELETE. Deferred root-current-version reference and consistency checks prevent root without corresponding evidence. Current linked incident status is not rewritten into old inspection revisions. |
| crop_health_command | Feature-local actor+key PK, normalized method/path/body SHA-256 + normalization version, reservation/completion time and original successful status/body/Location. SELECT/INSERT/reservation-completion UPDATE only. Completed receipt immutable; incomplete receipt cannot commit. No body retention on failed commands. |
| existing outbox_event | Reuse existing relay and owned table. One new event per changed root revision, not one private object serialization. Stable eventId survives delivery retries. Existing payloads/routes/grants remain unchanged. |

Use internal same-estate reciprocal link constraints and deferred consistency checks: an atomic two-root insert must be possible, but neither orphan/one-way link nor mismatched identity/version/snapshot may commit. No identity, provenance or original-link reassignment. Each root's JSON snapshot/indexed columns and revision must agree. Existing linked status is contextual GET data; immutable inspection evidence retains its own fields and link only. Root status does not advance just because its linked root changed.

## Transaction sequencing and races

1. Independently validated JWT → feature permission and explicit finite estate scope. Minimal ownership lookup only before protected data. Denied/missing IDs follow the uniform 404 policy after role validation; explicit foreign estate filter is 403. Authorize current operation/scope/response projection before receipt replay. Do not inherit Access.admin()'s PLANTATION_ADMIN union.
2. Normalize closed request fields, trim surrounding Unicode whitespace, normalize explicit offset instants to UTC microseconds and preserve signed subject exactly. Reserve feature actor/key with bounded lock wait. Original identical success replays without touching roots; method/path/body mismatch conflicts. Recheck permission downgrade before returning any body.
3. For linked commands lock inspection first, incident second, including incident void/resolve/correction. A minimal immutable link lookup can determine the inspection ID; revalidate it under locks. Commands require both versions iff linked. Direct incidents lock only their own root. Never invert order when a command starts at an incident URL.
4. Read/validate root invariants and all retained closure chronology. The pure domain's earliestRetainedResolutionAt is a private lower observation boundary: correction must not move observation past any retained resolution, even after reopen and subsequent resolution. Preserve/verify this boundary against revisions; do not serialize it into public events.
5. New evidence (including CLEAR→first FOLLOW_UP) requires bounded delegated active Estate/block validation. Historical correction/transition retains stored canonical context and needs no active/geometry lookup. A reference validation is point-in-time, not a distributed transaction; never lock another service's tables.
6. CAS update/insert changed roots, append matching immutable revision(s), explicit minimal outbox event(s), then complete original-success receipt, in one local transaction. Paired linked create/correction has two root events; transport can reorder them. Never hold a DB transaction waiting for broker confirm; reused asynchronous relay owns that step.

Required races: inspection correct-to-CLEAR versus incident reopen/void; inspection void versus incident change; two first-FOLLOW_UP commands; stale paired root version after unrelated linked change; two identical/changed key commands; two actors observing the same block/time (legitimate separate roots); reservation timeout; failure after first root/revision/event but before pair/receipt. A correction changing only private text still advances the root and emits a minimal version event. No fresh-key no-op revision.

## Verification before persistence acceptance

Use generated disposable PostgreSQL resources only. Prove fresh V1–V5 and populated V4→V5 upgrade retain all legacy rows/bytes, failed DDL rolls back, restricted runtime cannot DDL/delete/rewrite revisions/completed receipts, no cross-database access and no partial linked evidence can commit. Run a pinned prior-WA application image against retained V5 with migrations disabled; rollback retains evidence rather than dropping tables. Demonstrate restore into a separate disposable DB and preserve all existing volumes.

Before HTTP acceptance add signed-JWT reference/scope/role/denial/replay tests and actual runtime comparison. Before event acceptance exercise real broker confirms/returns/nacks/outage/ACK-crash duplicate handling. Pure II-T02 tests prove rules only for supplied state, not DB locking, privileges, reference validation or enforcement of these ports. Current source has no Spring crop-health bean/controller/JDBC adapter/event producer, and no mutation is exposed.

No retention period, purge task or REAL enablement is invented. II-D07/08 and WA D08/D09 remain open. New module text and actor metadata are potentially personal even with crop-only intent; reports/revisions/receipts/logs/broker/DLQ/backups need separately approved implemented retention/deletion policies. Current module candidates and domain admit SIMULATED only.
