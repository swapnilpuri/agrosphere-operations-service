# AN-1 persistence and runtime handoff — 2026-10-31

Status: schema/locking/test preparation only. AN-T02 pure rules/ports are implemented; AN-T03 migration/JDBC/runtime work has not started because its stable independently reviewed schema prerequisite is pending. See Architecture ALERTS_NOTIFICATIONS_APPROVAL.md and Contracts docs/alerts-notifications/CANDIDATE_REVIEW.md. No new migration, Spring bean, route, consumer or source transaction hook is registered.

## Prepared domain boundary

`alerts/domain/AlertRules` validates immutable minimal source facts, SIMULATED-only inputs, canonical issuer/opaque subject and positive feature grants. `CropHealthAlert` evaluates accepted incident before/after revisions, detects source gaps/equal-version conflicts, preserves one alert identity, advances shared alertVersion, manages attention and immutable acknowledgements, and implements explicit own-read semantics. `alerts/application/AlertPorts` defines scoped query results/counts, source-transaction rule port, locked writes and original-success receipt authorization. These are internal types, not wire serializers: never serialize private domain records directly.

Source versions start at zero; alertVersion and attentionVersion start at one. No alert exists for an incident that never qualifies. An older incident may first qualify on a future accepted revision, with raisedAt=command time, not its old observedAt. Existing alert source revisions must be adjacent; repeated identical sourceVersion has no new effect, conflicting equal versions fail. Existing source successful-receipt replay bypasses rule work entirely. Rule reads no network/broker and emits no shared alert event.

The adapter must map only actual incident revisions, including linked FOLLOW_UP creation, through the local rule port inside the existing transaction. Inspection-only revisions cannot generate a second alert. Existing source schemas/outbox serializers and V1–V5 remain unchanged. The source's accepted lifecycle remains authoritative; the alert domain does not replace its rules.

## Proposed additive persistence

Recheck migration head before choosing the next version after V5. Create only service-owned alert, transition, own-read, immutable acknowledgement and original-success command receipt tables as defined in the accepted review. Unique incidentId/ruleVersion, alertId/alertVersion evidence, alertId/attentionVersion shared acknowledgement and alertId/issuer/subject read markers must be DB-enforced. Source/alert/attention versions must fit signed int64 and never wrap. Check eligibility/state, null acknowledgement pairs, SIMULATED provenance and time chronology; preserve current versus historical acknowledgement separately.

Use existing restricted runtime/migration separation. Grant only required selects/inserts/updates; immutable transitions/ack evidence cannot be updated/deleted by runtime. No source-table rewrite, historical backfill, credential/role provisioning or existing-stack migration. Actual retention/purge/cascade design is deferred to AN-D07 policy; do not invent a duration or silently adopt crop-report/worker retention.

Source lock order: existing source command reservation -> linked inspection -> incident -> alert -> alert-specific rows/evidence. Own-read/ack lock order: own feature receipt reservation -> alert -> own marker/ack/evidence. Do not acquire source locks after an alert lock. Resolve the incident/rule unique-insert race within the same source transaction. Failure to persist alert/evidence rolls back source revision/outbox/receipt. Source changes and read/ack serialize under the alert lock; version checks follow lock acquisition.

One trusted issuer/subject owns a receipt key, scoped to alerts; store normalized method/path/body hash and original HTTP 200 body. Failures save no success receipt. Recheck current operation/estate/identity and retained response permission before replay, including downgrade/wrong issuer. Do not log body/hash inputs containing personal data, token or private configuration. Read/ack responses use an explicit ordinary-view allowlist. Shared ack actor appears only in protected history; other users' read state is never exposed.

Query indexes should serve estate/state/attentionAt/id paging and active counts, source identity, protected history and own marker joins. SQL scope applies before count/history/materialization. Page*size <=10000, size 1–100; list and count each use a consistent request snapshot and generatedAt. No all-estate aggregate or recipient enumeration. Scope/lifecycle can change between requests; generatedAt is not a delivery SLA.

## Tests required before runtime acceptance

- Generated disposable PostgreSQL: fresh/upgrade from populated V5, failed-DDL rollback, retained old rows/migrations, restricted grants/evidence denial, unique races, versions/overflow, atomic source+alert rollback and receipt replay.
- Concurrent source/ack/read: first shared ack wins; stale source/attention/version conflicts; no reverse lock order; old own read cannot consume a new episode; identical receipt replay is original success after current authorization.
- No-backfill proof with preexisting qualifying incidents; then one future accepted incident command creates exactly one alert. Linked creation plus correction/triage/resolve/reopen/void exercise actual rule adapter. Broker outage cannot delay committed inbox visibility; old outbox recovery stays unchanged.
- Restore populated alert/read/ack/evidence/receipts into a separate generated DB. Prior II-1 image runs with retained additive schema, migrations disabled; a later restart must not invent catch-up alerts for commands the old image never evaluated. Document this rollback limitation and keep rules disabled until explicitly restored; no automatic replay/backfill.
- After exact candidate release/pinning: real signed JWT and Gateway HTTP scopes/roles/foreign IDs/history/ordinary projection/closed fields/no-store/bounds/receipt tests, strict actual six-operation contract comparison plus all old 58 operations.

AN-D07 must define how to gate rule evaluation and every alert endpoint outside generated tests. Do not enable the feature for existing accounts merely because source reports are SIMULATED. AN-D08 supplies actual load/language/connectivity budgets. The accepted proposed 30-second Web poll is not measured performance.

## Safe continuation

Accept/revise exact executable artifacts first; record independent local release/promotion/pins if accepted. Then implement AN-T03 under the stable reviewed schema and continue AN-T04 onward by dependency. No existing stack/private configuration, data/volumes/demo geometry, roles/credentials, REAL, external notification, Git publication/deployment or AEO action. Keep owner functional acceptance separate from engineering checks.
