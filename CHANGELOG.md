# Changelog

## Unreleased — 2026-10-29 WA-1

Added WA-1 domain, additive V4 persistence/grants, durable receipts, fifteen scoped endpoints, bounded authenticated Estate references and four minimal v1 event families. Pin accepted Contracts 0.6.0 while preserving prior 0.5.0 hashes/legacy semantics. REAL mutations remain denied. Add signed-JWT/database/broker/runtime-contract/prior-image checks; see docs/WORKFORCE_IMPLEMENTATION.md. No deployment or publication.

## Unreleased - 2026-10-28 CI artifact preservation

Preserve exact owner-approved Contracts 0.5.0 artifact bytes in Git rather than normalizing their CRLF line endings. Add an isolated Git clean/checkout regression for both autocrlf settings before integration CI. Existing hashes, contract semantics and strict integrity checks are unchanged.

## Unreleased - 2026-10-27 local Milestone 3B

Collection command/API/query/v2 outbox implementation with durable replay, scoped exact-decimal summaries, separate runtime/Flyway credentials, generated-only integration/backup/prior-image drills and strict 27-operation validation. Existing tapping semantics and v1 event bytes retained. Local implementation only; no deployment or remote publication.

## Unreleased - 2026-10-27 persistence and references

Pinned owner-accepted local Contracts 0.5.0 artifacts. Added V3 evidence/receipt constraints and restricted grants, scoped JDBC collection persistence, exact wire snapshots, positive collection permissions and typed fail-closed Estate reference validation. Disposable schema/store tests and signed JWT/reference tests added; credential wiring remains excluded and collection HTTP/application replay/event flows remain incomplete. No deployment or active database action.


## Unreleased — 2026-10-27

T02 collection domain/calculation and 22 fixed-clock boundary/lifecycle tests; preserves existing tapping source, API/event pins and V1/V2 migrations. Java 25 Maven verify: 56 unit/security tests passed. No collection API/persistence or runtime release yet.


## 0.2.0 â€” 2026-10-24

Implement Milestone 3A rubber tapping domain, REST/JWT estate authorization, PostgreSQL migrations, transactional confirmed outbox, productivity, independently pinned contracts and container tests.

## 0.1.0 â€” 2026-10-23

Initial Milestone 0 foundation. No production business capability is delivered.
