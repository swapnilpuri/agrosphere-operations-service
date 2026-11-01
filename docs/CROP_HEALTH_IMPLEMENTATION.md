# II-1 inspections and crop-health incidents

Local/unpublished implementation of the owner-approved design and exact Contracts 0.7.0 artifacts. Operations owns the domain, additive V5, scoped JDBC, signed-JWT policy, sixteen HTTP operations and four minimal event families. No new service, master-data copy, geometry or cross-service SQL is introduced. V1–V4 and prior contract archives remain unchanged.

## Commands and authorization

Completed block inspections and incidents have separate UUIDs and immutable estate/block/provenance/link attribution. CLEAR records evidence only; FOLLOW_UP atomically creates its first linked OPEN incident. Direct incident reporting does not invent an inspection. A retained link cannot be reassigned or used to create a second incident.

Feature-specific positive permissions require signed subject and trusted estate_ids: PLATFORM_ADMIN globally, scoped ESTATE_ADMIN/ESTATE_MANAGER manage, FIELD_SUPERVISOR current-read/today-create, AUDITOR current/history-read. Other roles, including PLANTATION_ADMIN alone, do not grant access. Lists require one explicit estate. Missing/foreign object IDs return uniform 404 after role validation; explicit denied estate filters return 403. Ordinary DTOs omit actors and revision reasons; history is separately protected.

All commands require UUID Idempotency-Key. Input shapes are closed; duplicate JSON properties, unknown fields, null authority inputs, invalid enums/UUIDs/control text and overprecision are rejected. Normalize Unicode surrounding whitespace and explicit offsets before hashing, preserving signed subject exactly. Existing roots require expectedVersion (inspection commands use inspectionVersion), plus the linked root version iff linked. Same actor/key/normalized method/path/body returns original status/body/Location; current permissions/scope are checked before replay. A manager-to-supervisor downgrade denies replay; supervisor replay also rechecks the estate-local date. New-key no-op returns 409.

The command clock freezes once at transaction entry so root audit, evidence and event timestamps agree under a real clock. Observations require an explicit offset, up to microseconds and no future time. Managers may backdate with a reason; supervisors create only estate-today. Reference validation is delegated, bounded, size-capped, no-redirect and fail-closed against active canonical rubber estate/block metadata. Historical corrections/transitions retain context and do not depend on current activity or geometry. New first-linked evidence revalidates active references and requires explicit review if canonical timezone changed.

## Lifecycle and transaction consistency

Incidents move OPEN→IN_REVIEW; OPEN/IN_REVIEW→RESOLVED with time/note; RESOLVED→OPEN clears current resolution fields while retaining history. Any nonvoided status can be voided. VOIDED is terminal. Amendments are open-state reassessments; evidence correction also permits RESOLVED, without changing lifecycle. A private earliest retained resolution boundary prevents moving the observation past any retained closure, including after multiple reopen/resolve cycles.

An inspection cannot become CLEAR or VOIDED until its linked incident is VOIDED. That link remains historical evidence. Incident changes never silently rewrite inspection outcome or version. Current inspection DTOs include latest linked status/version; immutable inspection revisions contain their root/link only.

Reserve feature actor/key first; then lock inspection before incident, even for incident URLs. Validate both versions under those locks. Persist changed root(s), revision(s), original-success receipt and outbox event(s) in one local transaction. Deferred reciprocal link/current-revision/outbox checks and append-only guards reject partial evidence. Successful HTTP commit is independent of asynchronous broker delivery. Scoped lists/counts use a repeatable-read request snapshot before pagination, ordered observedAt DESC,id DESC; ranges are half-open, size 1–100.

V5 grants required operations only on new owned tables to the already-established restricted runtime role; it creates no role/login/password. Runtime cannot delete roots, rewrite revisions/completed receipts or execute DDL. Existing runtime/Flyway connection separation is preserved. All migration/privilege tests run on generated disposable PostgreSQL resources, never an existing stack.

## Events, recovery and privacy

New crop-health/v1 event types: FieldInspectionRecorded/Changed and CropHealthIncidentReported/Changed. Every changed root emits one explicit minimal snapshot, including private-only changes. Shared payloads omit summary/details/findings/reasons/resolution notes and actor subjects. Identities, versions and provenance agree with evidence. Legacy CropHealthIncidentDetected and existing event families are preserved, with no dual publishing.

The existing mandatory-confirm relay retains stable event IDs through retries/outage/ACK ambiguity and bounds failed delivery. Infrastructure has an explicit bounded durable acceptance sink and DLQ; no business consumer or notification delivery ships. Paired events can reorder. The test-only projection harness exercises atomic dedup, stale/duplicate versions, gap reconciliation, conflicting equal versions, tombstones and unresolved links without fabricating records.

All new mutations remain technically SIMULATED-only at domain/HTTP/database boundaries. No named personnel cases, account assignment, attachments, offline cache, bulk/export, automatic diagnosis or alert dispatch. II-D07/08 and WA D08/D09 remain open: real purpose/access/retention/deletion across reports/evidence/receipts/logs/broker/DLQ/backups and actual workload/languages/connectivity must be reviewed and verified. No indefinite-retention or performance acceptance is inferred.

## Validation and rollback

Use independent Maven verification/integration plus scripts/validate_contracts.py, validate_collection_pins.py, validate_workforce_runtime.py and validate_crop_health_runtime.py after fresh integration exports. Run all projection/comparator/byte-checkout regressions. Integration covers real signed HTTP, restricted runtime, paired rollback/races, failed V5 DDL rollback, original replay/revocation, real broker recovery and prior-WA-image compatibility with retained V5 evidence. Architecture verification/evidence records actual command results and remaining limits.

Application rollback retains V5 and evidence. Do not run a destructive down migration. Prior images run with Flyway disabled in the isolated compatibility drill; re-upgrade to read new records. Backup/restore must target a separate disposable database for verification. No existing local stack upgrade is authorized by code verification; preserve all existing records, volumes and SIMULATED demo geometry. No commit/push/publish, production, private-environment, credential/role provisioning or AEO changes.
