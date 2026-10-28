# AgroSphere Operations Service â€” 0.2.0

Milestone 3A implements rubber tapping schedules, dated assignments, execution/missed records and live productivity. This is an independent Java 25 / Spring Boot 4.0.9 / PostgreSQL service. Milestone 3B collection/DRC domain, persistence and security components are under construction; no collection HTTP API is enabled yet. Forecasting and an analytics warehouse remain excluded.

## Build and verify

Use JDK 25 and Maven Wrapper 3.9.12: `./mvnw.cmd verify` on Windows or `./mvnw verify` on Linux. Run ./mvnw verify -Pintegration with Docker for real PostgreSQL/RabbitMQ tests and signed JWT HTTP tests. JUnit 5.14.4 uses explicit Spring contexts; do not add SpringExtension. Install requirements-dev.txt in an isolated Python environment, then python scripts/validate_contracts.py after integration. The contract check intentionally fails when runtime exports are absent. CI executes all checks, packaging and docker build.

Dockerfile uses pinned Maven 3.9.12 / Temurin 25, executes unit tests, and produces a non-root JRE image. Flyway V1 remains unchanged; V2 adds operational tables, constraints, filters and outbox retry columns. PostgreSQL belongs only to Operations. JDBC uses application ports and local transactions; there are no shared JPA entities.

## Runtime

Set DB_URL, DB_USER, DB_PASSWORD, FLYWAY_USER, FLYWAY_PASSWORD, OIDC_ISSUER_URI, RABBITMQ_USER and RABBITMQ_PASSWORD privately. OIDC_JWK_SET_URI can select internal Keycloak certificates while preserving public issuer validation. OIDC_AUDIENCE defaults to operations-service. ESTATE_SERVICE_URL defaults to http://localhost:8081; ESTATE_TIMEOUT_SECONDS defaults to 3 (1â€“30). OUTBOX_PUBLICATION_ENABLED defaults true; EVENT_DATA_PROVENANCE defaults REAL (use SIMULATED locally). Spring does not load .env directly.

Server port 8083; health/liveness/readiness are public, business routes and /v3/api-docs require valid JWT. Readiness checks the local database. /actuator/metrics requires authentication and exposes operations.outbox.backlog and operations.outbox.failed. Audit times are UTC instants; requests carry estate-local ISO dates. Correlation header X-Correlation-ID is a UUID; missing/invalid values are replaced and returned. Logs use structured JSON without credentials. Shutdown is graceful and Hikari pooling is bounded.

## API

All business endpoints start /api/v1/tapping. Full independently versioned OpenAPI snapshot: src/main/resources/contracts/operations-service.yaml, from locally accepted Contracts 0.5.0 (18 tapping and 9 collection operations).

| Resource | Methods / suffixes |
|---|---|
| /schedules | POST create (201), GET paginated list |
| /schedules/{id} | GET, PATCH draft configuration |
| /schedules/{id}/activate, /suspend, /close | POST lifecycle with version |
| /schedules/{id}/generate-assignments | POST version/from/to/workerId; at most 367 inclusive dates |
| /assignments | POST authorized ad hoc work (201), GET list |
| /assignments/{id} | GET assignment/execution/missedReason |
| /assignments/{id}/worker | PATCH planned worker reassignment |
| /assignments/{id}/start, /complete, /mark-missed, /cancel | POST transition |
| /records | GET paginated execution records |
| /summary | GET totals or groupBy=worker/block; grouped rows paginated |

Lists accept estateId/blockId/status where applicable; assignments and records accept workerId/from/to. Dates are inclusive, page starts at 0, size 1â€“100 (default 20). Summary returns counts and trees; its denominator includes all assignments, including cancelled. Empty completion percentage is zero. Actual trees count only completed execution records.

Mutations send observed version. Draft PATCH replaces editable configuration, with estate/block identity fixed. Complete requires a prior start; actual trees must be 0..planned. Completion/missed identical retries return persisted data and do not emit new events. Differing retry payloads fail. Terminal work is immutable. Creates do not promise replay semantics; inspect list results after ambiguous create responses. Generation is retry-safe by schedule/date. Cancelling or closing a schedule never deletes history.

Errors use application/problem+json: 400 invalid request/state, 401 bad JWT, 403 scope/role/ownership denied, 404 unknown resource, 409 uniqueness/version conflict, 422 invalid Estate reference, 503 Estate unavailable. No Estate fallback is synthesized.

## Security

| Realm role | Operations permissions |
|---|---|
| PLATFORM_ADMIN, PLANTATION_ADMIN | All estates/workflows |
| ESTATE_ADMIN, ESTATE_MANAGER | Schedules and assignments within trusted estate_ids |
| FIELD_SUPERVISOR | Scoped assignments and execution; schedules read only |
| FIELD_WORKER | Scoped own assignments/records/summary; start, complete, missed only |
| AUDITOR, VIEWER | Scoped read only |

workerId is the Keycloak subject, not a username or authorization credential. Every object and query is checked inside Operations. Estate validation delegates the caller JWT; the realm supplies both operations-service and estate-service audiences. Estate operational roles have scoped master-data read permissions only.

## Events and recovery

Six operations/v1 events: TappingScheduleActivated, TappingAssignmentCreated, TappingAssignmentStarted, TappingCompleted, TappingMissed, TappingAssignmentCancelled. Exchange agrosphere.domain; routing agrosphere.<type>.v1. Strict schemas are pinned under src/main/resources/contracts/events. Stable eventId, eventVersion=1, timestamp, source, correlationId, estateId, blockId, dataProvenance and aggregateId/version follow existing platform conventions. Trace metadata does not change the envelope.

The transactional outbox only publishes committed state, waits for confirms, checks mandatory returns, and retries with capped exponential delay. Delivery is at least once. Consumers must deduplicate eventId with their own database writes. operations.outbox max-attempts (10), batch-size (20), retry-seconds (5), confirm-timeout-seconds (5), poll-ms (1000) are configurable Spring properties. Failed rows remain inspectable. After correcting the cause, an authorized operator may reset selected FAILED rows to PENDING with next_attempt_at=now(); retain their IDs.

Architecture decisions, seven PlantUML diagrams, examples and verified results are in the independent Architecture repository, docs/milestones/milestone-3a-*.md and ADR 0012.

## Milestone 3B local implementation (unreleased)

Collection routes under /api/v1/tapping/collections support creation, scoped list/get/history/measurements, initial DRC, corrections, void and summaries. Every write requires a UUID Idempotency-Key. Receipts retain the original status/body/Location even after later revisions; current role/estate/worker authorization is checked again before replay. Normalization policy 1 treats equivalent decimals, offsets and normalized references consistently. A competing reservation waits at most three seconds and returns retryable 409; retry with the original key.

Weight uses exact JSON decimals (three places), DRC two places, and dry mass HALF_UP to three places. No DRC means pending/null; measured zero remains calculated. Observations require explicit offsets and exact microsecond representation (additional trailing zeros accepted). Snapshot estate zone, division, versions and ownership remain historical. Both date bounds are optional for lists; a single bound is invalid. Summaries require both bounds and at most a 366-day difference, keep provenance separate, sum rounded lots and report pending coverage. Grouping supports total/estate/division/block/worker/date.

V3 is additive and requires a separately provisioned restricted agrosphere_operations_app login. DB_USER/DB_PASSWORD select runtime; FLYWAY_USER/FLYWAY_PASSWORD select the migration owner, without credential fallback. Public .env.example contains placeholders only. The Infrastructure OPERATIONS_RUNTIME_CREDENTIALS runbook documents operator sequencing; no existing private file or running stack was provisioned. Integration tests use generated logins, prove denied DDL/evidence mutation/cross-database access, restore backups into a separate disposable database and run the pinned prior 3A image against retained V3 evidence with migrations disabled. Application rollback retains V3 tables and audit; no automatic down migration is supplied.

Four collection event types use v2, full current snapshots and one event per committed revision; tapping remains v1. Infrastructure declares a separate durable bounded development sink with four explicit v2 bindings. Real broker tests cover confirms, mandatory returns, nacks, confirm timeout, outage recovery and duplicate delivery after an ACK/database crash window. Delivery remains at least once; scripts/test_collection_projection.py is an isolated test-only reconciliation harness, not a shipped Analytics consumer.

After ./mvnw.cmd verify -Pintegration on JDK 25, run:

~~~powershell
python scripts/validate_collection_pins.py --snapshots target/collection-snapshots --events target/collection-events --api-responses target/collection-api
python scripts/validate_contracts.py
python -m unittest discover -s scripts -p test_contract_comparison.py
python scripts/test_collection_projection.py
~~~

Use requirements-dev.txt in an isolated Python environment. Collection OpenAPI documentation is contract-first: the customizer verifies actual registered paths/methods, then applies the pinned operation/schema definitions. The strict comparator checks all 27 operations and constraints, normalizing only required-field ordering and local references. Separate HTTP integration tests validate enforcement and actual responses; documentation equality alone is not runtime proof. Target exports/logs are ignored and regenerated by integration tests. Milestone acceptance and outstanding checks are recorded in Architecture DEVELOPMENT_CHECKPOINT.md; no production deployment or remote publication is implied.
