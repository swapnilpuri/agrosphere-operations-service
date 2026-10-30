# WA-1 local implementation

Owner design/executable approvals are recorded in Architecture. Accepted Contracts 0.6.0 hashes are pinned in `src/main/resources/contracts/WORKFORCE_RELEASE.json`; the prior 0.5.0 API is archived. Historical OpenAPI info.version remains 0.2.0 to preserve approved bytes. Application changes remain Unreleased.

The workforce package owns immutable registrations, active periods and daily attendance. Additive V4 preserves V1–V3 bytes and adds SIMULATED-only roots, permanent estate/code and worker/date uniqueness, deferred root/revision/period consistency, immutable evidence/receipts and locking/eligibility guards. It reuses the existing restricted runtime and separate migration-owner credentials. Runtime cannot perform DDL or edit/delete evidence. No destructive down migration is supplied.

Fifteen new endpoints bring the runtime to 42 operations. Positive roles remain separate from legacy role unions. Current role/estate checks precede data/count/history/receipt access. Ordinary views omit actors/reasons. Delegated Estate references use signed JWTs, a fixed origin, bounded bodies/deadlines and no redirects. Reviewed historical paths allow inactive master data; reference outages fail closed.

Closed requests are normalized before actor/key hashing. Reservation, root/period/revision changes, original success receipt and outbox commit atomically. Stale/conflicting commands return 409. Identical retries return original body/status/Location after current permission checks. Worker locks serialize closure with daily recording. Four minimal v1 event families omit names, codes, reasons, subjects and fabricated block attribution. Legacy emits/relay behavior remain intact.

## Verification and rollback

WorkforceStoreIT covers V3 upgrade, failed V4 DDL rollback, roles/races/constraints. WorkforceServiceIT covers signed-JWT HTTP, bounded Estate responses, runtime contracts, actual responses/events and broker ACK/return/crash behavior. CollectionCredentialsIT adds prior 3B `a808c199f87562cb3469f4d612e22e5f894ab660` against V4 with retained workforce evidence, alongside original 3A compatibility. Old applications cannot operate workforce endpoints: retain additive schema/evidence and disable workforce writes during an independently approved rollback. Tests use generated disposable resources only; no existing environment rollback is authorized.

Run Maven verify -Pintegration, exact checkout/pin/runtime validators, contract comparison tests and collection/workforce projection harnesses. The workforce harness exercises duplicate IDs, reordered/stale snapshots, gap flags, tombstones, equal-version conflict and atomic dedup in memory. No production Analytics consumer is shipped.

D09 actual retention/deletion/purpose rules are absent. Do not enable REAL data, invent expiry periods or erase evidence. Domain and DB enforce SIMULATED-only. D08 agreed workload/latency, owner functional sign-off and production readiness remain separate gates. Keep personal payloads out of operational logs/shared events. Test exports contain SIMULATED fixtures only.
