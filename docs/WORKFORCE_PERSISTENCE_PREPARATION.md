# WA-1 persistence preparation — 2026-10-29

Status: design/preparation only, not an executed migration or completed WA-T03. The owner accepted WA-1; executable Contracts candidate review/stability remains pending. Do not place draft SQL on the Flyway classpath or run against the existing stack before that gate. Current migration head remains V3__latex_collection.sql. V1–V3, application configuration, database contents and runtime credentials are unchanged.

## Source findings

V3 explicitly validates the existing `agrosphere_operations_app` role is nonprivileged, has no role memberships and does not own the database/schema. It grants SELECT/INSERT/UPDATE on current roots/command reservations/outbox and SELECT/INSERT on collection evidence. Append-only triggers and deferred completed-receipt constraints supplement permissions. These are source observations, not a live role/privilege audit. New tables do not automatically receive those explicit grants.

The existing outbox table stores aggregate/type/version/correlation/time/payload and does not require a block column. Relay routing is version-aware; legacy `DomainEvents.emit` takes a block UUID, so a new workforce event writer must use its own port/explicit envelope, not invent a block or change the old signature. No new outbox table or broker consumer is needed.

`workforce.domain` contains immutable roots, periods, attribution, audit, explicit permissions and the SIMULATED-only guard. `workforce.application.WorkforcePorts` defines finite read scopes, minimal ownership lookup, root locks, revision/receipt contracts and an atomic command boundary. No Spring beans, HTTP endpoints, persistence adapters, scheduled jobs or runtime wiring are introduced by those files.

## Next migration design

Tentative next migration is V4; refresh heads before authoring, do not assume the number remains free. Tables are local to Operations:

| Table | Required constraints / access |
|---|---|
| worker_registration | UUID PK; immutable estate/code/provenance; permanent estate+normalized-code uniqueness; optional external division UUID; profile/version/audit fields. SELECT/INSERT/UPDATE current projection, no DELETE. SIMULATED-only CHECK until D09 enablement is separately reviewed. No Estate DB FK. |
| worker_active_period | UUID PK and internal registration FK; exclusive end > start; introduced/closed root versions; unique open period; root-serialized overlap checks and immutable period identity. SELECT/INSERT and constrained closure UPDATE only. No extension creation or elevated privilege assumed. |
| worker_revision | Internal registration+version PK, immutable full private snapshot, kind/reason/actor/time/correlation. SELECT/INSERT only, reject UPDATE/DELETE. Root current-version FK may be deferred to transaction commit. |
| attendance_day | UUID PK; internal registration+estate consistency FK; permanent estate/registration/date uniqueness including VOIDED; current status/version/context/audit. Identity/provenance fixed; contextual snapshots refresh only on explicit re-record. SELECT/INSERT/UPDATE current projection, no DELETE. SIMULATED-only CHECK. |
| attendance_revision | Internal attendance+version PK; full immutable private snapshot and command evidence, including backdate reason. SELECT/INSERT only, reject UPDATE/DELETE. No soft-delete of evidence. |
| workforce_command | Actor+key PK, normalized method/path/hash/policy version; reservation and original successful status/body/Location; root kind/id/version and completion time. Retain opaque signed actor exactly. SELECT/INSERT and reservation-completion UPDATE only; reject mutation of completed receipt and incomplete commit. |

Use explicit grants to the existing runtime role within the service-owned migration; no role creation/password rotation, provisioning-tool invocation or new credentials. Apply existing role drift checks again. A new privilege/identity model beyond these reviewed table grants returns to scope review. Foreign UUIDs are validated over bounded authenticated Estate APIs, not cross-service SQL.

## Transaction and lock order

1. Verify signed identity, positive operation permission and minimal ownership. Reject unauthorized requests before evidence or receipt lookup. Resolve requested estate explicitly, including platform reads; finite SQL predicates precede pagination/counts/history.
2. Normalize command inputs without modifying signed subject. Reserve actor/key with bounded timeout. Exact completed replay checks current permission first and returns original result; different method/path/hash conflicts without exposing stored bodies. No framework retry that generates a new key.
3. Lock worker_registration before reading active periods or locking an attendance_day. Worker profile/period change, new attendance and re-record all use that same root lock. Then lock attendance root if applicable. Never reverse worker→attendance order. Registration inserts rely on permanent code uniqueness.
4. Read all retained nonvoided attendance dates under that lock before closing a period. Reject a close that excludes any date. A simultaneous attendance command must wait and then validate the committed periods. Pure domain tests demonstrate the invariant for supplied dates; only PostgreSQL concurrency tests can prove adapter enforcement.
5. Apply expected version, write current root/period projection, immutable revision and minimal outbox event, and complete the receipt in one local transaction. Deferred constraints reject partial evidence/receipts. HTTP commit is independent of asynchronous broker confirmation.

## Queries and privacy

Scope predicates apply before all data/counts/history. Roster uses current eligible active periods at requested date and current division filtering, not historical snapshot reparenting. Count before paging: eligible, marked, unmarked, voided, and separately nonvoided outside-roster evidence, with provenance required for roster. Ordinary DTOs omit signed actors/reasons; only manager/auditor history exposes protected evidence. The current code does not yet implement these query adapters or HTTP guarantees.

No retention duration or purge job is defined by this preparation. Do not copy collection's indefinite retention semantics. D09 must address roots/revisions/receipts/outbox/broker/DLQ/logs/backups before REAL enablement; the current domain guard remains unconditional SIMULATED-only. Future real enablement requires reviewed code/schema/policy evidence, not a runtime boolean.

## Required WA-T03 verification

After candidate review: implement fresh and V3→V4 upgrade on generated disposable databases; compare V1–V3 bytes and retained 3A/3B rows; test DDL rollback on failed migration, restricted runtime grants and revision/receipt mutation denial. Race duplicate code/daily writes, stale versions, period close versus record/re-record, evidence rollback and bounded reservations. Run the old application image on retained additive schema before compatibility acceptance. Do not reset or migrate authoritative/existing volumes. WAL/backups and any future privacy deletion procedure require their own reviewed evidence; no destructive down migration is proposed.
