# Privacy observer candidate review

Date: 2026-11-01. Scope: PM01 design review and PM02 generated candidate validation only.

The owner requested the next observer SQL/privilege step. The exact [candidate SQL](privacy-maintenance/privacy_observe_candidate.sql) is deliberately outside `src/main/resources/db/migration`. V1–V7 and all contract pins remain unchanged. This file is not V8, not installed by application startup and not permission to alter an existing database. Generated integration tests install it only in fresh Testcontainers schemas. No scheduler, runner, live collector or trusted independent ledger is delivered here.

## Static review before generated application

Reviewed by Codex for generated testing on 2026-11-01; this is engineering review, not owner/client acceptance or a production security sign-off. The evidence records the tested candidate SHA-256. No external or parallel reviewer is claimed.

The installer captures `current_schema()` once and quotes it as an identifier in the function definition. All service tables are schema qualified; the function accepts no parameters and executes no dynamic SQL. The installation DO block is owner-only migration preparation, never a runtime endpoint. `privacy_observe()` uses SQL STABLE, SECURITY DEFINER, `search_path=pg_catalog,pg_temp` and UTC. PUBLIC EXECUTE is revoked in the same transactional installation. It creates only the observer and changes no existing table/function/grant. See PostgreSQL's [function security guidance](https://www.postgresql.org/docs/17/sql-createfunction.html) for the search-path and default-execution considerations.

The future installer must verify the explicit target, migration fingerprints, schema owner, trusted schema CREATE rights and function owner before installation. Generated tests use the disposable migration owner; that does not approve a superuser production owner. Owner selection and least-privilege installation remain selected-environment OR01 gates. A future generated-only runner must also verify effective privileges and existing V7 captured search paths; the observer does not make V7 immune to temporary-table substitution.

| Principal | Generated test authority | Required denial |
|---|---|---|
| Runtime | Existing inherited application role, unchanged | Observer, cleanup, removal, DELETE and DDL |
| Monitor | Exact schema USAGE, observer EXECUTE | Cleanup/removal, raw privacy tables and schema CREATE |
| Cleanup | Exact schema USAGE, observer and cleanup EXECUTE | Removal, raw privacy tables and schema CREATE |
| Removal operator | Exact schema USAGE, request-removal EXECUTE | Observer/cleanup/raw tables unless separately reviewed |

Tests use generated role names/passwords only in their fresh disposable PostgreSQL container. No actual credentials are read or provisioned. Effective inherited/PUBLIC permissions and temp-table shadows must be tested, not inferred from intended grants.

## Output and eligibility review

One JSON aggregate contains `version`, internal `observerRevision`, database `observedAt`, `eligibleRootCount`, the five copy classes (`read`, `receipt`, `ack`, `evidence`, `ledger`) with count/nullable oldest eligibility, and pending-removal count/nullable oldest request. There are no identity, estate, alert, request, command or content fields. Counts remain restricted operator data. The future adapter must validate the observer revision and bind a verified target and policy before producing the watchdog envelope; raw output is not already that envelope or authenticated evidence.

| Branch | Candidate treatment |
|---|---|
| Inactive read/receipt | Original inactive time + 30 elapsed UTC days, inclusive |
| Inactive evidence/ack | Original inactive time + 365 elapsed UTC days, inclusive |
| Receipt expiry | Original `expires_at`, not a new 30-day period from observation |
| Verified removal | Matching original request/cutoff, inclusive read/completion comparison; pending requests remain visible even with no root copies |
| Read/receipt watermark | Eligible count included; original eligibility age UNKNOWN |
| Evidence watermark | Eligible count included; original eligibility age UNKNOWN |
| Orphan ack | Evaluate evidence surviving the same cleanup's earlier evidence deletion, not just the pre-cleanup snapshot; original age UNKNOWN |
| Ledger | Expired ledger counts/times even with zero roots |

When any eligible row in a class has unknown original age, that class's oldest time is null. SQL MIN ignoring unknown rows would understate age and could produce a false healthy result. Reopening can clear the original inactive timestamp; repeated history expiry can overwrite the last-history timestamp. Neither observation time, attention time nor latest expiry is substituted for the missing original deadline. Additive original-deadline metadata needs its own design/review before on-time removal can be claimed for those branches. No historical row is converted or timestamp fabricated here.

`eligibleRootCount` counts roots with eligible child copies, not roots locked or processed by cleanup. The observer models delete coverage, including receipts and ack rows that become orphaned after eligible evidence deletion. It does not use SKIP LOCKED. It can therefore expose stranded eligible work rather than certifying cleanup's selection is complete. In reachable application writes, receipt deadlines are already capped at the inactive read deadline; V7's standalone inactive-receipt delete branch is broader than its root-selection branch for arbitrary stored fixtures. The parity test explicitly distinguishes that synthetic mismatch. No V7 predicate is silently repaired.

The observer pins UTC to honor elapsed-day policy. V7 captures a search path but does not pin timezone; a future cleanup connection must enforce/verify UTC, with DST cases in PM05. This candidate alone does not establish that property for existing jobs. Database timestamps/snapshots remain separate from cleanup commits; observation is read-only evidence, not a completion certificate.

## Exit and promotion limits

The generated integration suite must prove role boundaries, read-only execution, output minimization, alternate/temp schema shadow resistance, exact deadline boundaries, combined known/unknown ages, removal cutoffs, ledger-only work, locked-root visibility and cleanup parity/source preservation. No public API/event change or Contracts release is needed for this internal candidate. Run the owning repository's full `mvnw.cmd verify -Pintegration` and pin/runtime checks; capture actual results separately.

PM01/PM02 remain partial while original watermark eligibility ages lack reviewed additive metadata and V8 promotion remains unperformed. PM03/PM05, actual role/owner installation, scheduler, live adapter, trusted ledger service and selected-environment OR01–06 remain open. Tests of this candidate do not execute AP-T08. Next engineering work is the additive metadata/V8 design, then generated runner/heartbeat and real failure/recovery integration; keep the existing stack, private configuration, records, volumes, geometry and Web configuration untouched.


## Generated review coverage

The initial independent Java 25 candidate run passed 13 cases (zero failures/errors/skips). Six parameterized cases exercise before/at/after 30/365-day database-time boundaries. The remaining seven cover effective role/configuration checks, read-only/output/shadow handling, receipt/removal cutoff behavior, known plus unknown watermark ages/post-delete orphan ack, locked-root zero returns, pending/expired ledger-only work and synthetic receipt selection mismatch. Full regression/pin results and final candidate/test hashes are recorded in Architecture's dated observer evidence.

This supplies candidate-level evidence for parts of PM-AC01/02/03/04/07/08. It does not cover authenticated maintenance logins, complete V7 temporary-object hardening, large-root capacity, connection interruption/ambiguous commits, runner budgets/atomic files, independent-ledger recovery or a full labelled platform drill. PM01/02 are partial; none of PM-AC01–10 or OR01–06 is accepted as a whole by this candidate run. Generated NOLOGIN roles exercised through SET ROLE prove effective SQL privileges, not authentication/provisioning/rotation of future job logins.
