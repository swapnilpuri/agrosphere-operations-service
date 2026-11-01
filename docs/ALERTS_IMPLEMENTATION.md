# AN-1 local implementation and recovery

Implemented locally, 2026-10-31, against independently accepted Contracts 0.8.0. See Architecture ALERTS_NOTIFICATIONS_CONTRACT_APPROVAL.md and ALERTS_NOTIFICATIONS_VERIFICATION.md. This is generated-only engineering verification, not live rollout or owner functional acceptance.

## Behavior

Operations maintains one SIMULATED crop-health alert per incident/CROP_HEALTH_HIGH_V1. HIGH/CRITICAL and OPEN/IN_REVIEW qualify. Future incident commands drive the deterministic rule inside the source revision/outbox/receipt transaction. No historical scan/backfill, broker consumer, notification event or external delivery exists. Linked FOLLOW_UP creates only its incident's alert. Alert write failure rolls back the source command.

Attention starts at one and renews on requalification or HIGH-to-CRITICAL escalation. Same-severity changes/triage and CRITICAL-to-HIGH do not reset attention. Source changes advance shared alertVersion; explicit own read does not. First manager acknowledgement advances alertVersion, records own read and immutable acknowledgement/transition evidence. It neither resolves nor assigns the incident. Source resolution/void/threshold reduction makes the alert inactive; earlier evidence is retained.

Six endpoints live under `/api/v1/alerts`: list, counts, detail, own read, shared acknowledgement and protected history. Independently signed issuer/audience/opaque subject/realm roles/trusted estate_ids are required. Managers read/manage/history, supervisors read/own-read, auditors read/own-read/history; PLATFORM_ADMIN selects an estate explicitly for list/count. Other roles alone grant nothing. SQL scope precedes counts/history; ordinary responses exclude source report text, identities of acknowledgers and other people's read markers. All responses are no-store.

Requests are closed and duplicate-safe; positive int64 versions retain exact integral JSON values without rounding. UUID actor-scoped keys identify normalized POST/path/body commands. Identical retry returns the original successful body after current permission/scope validation. A different command conflicts. An original success can describe an earlier alert version; refresh current facts afterward. Late read or stale ack never consumes a newer attention episode.

## Disabled outside generated tests

`operations.alerts.generated-test-mode` defaults to false. Alert endpoints return 503 after feature authorization, and source rule evaluation does no alert work. Generated tests additionally supply an explicit exact subject allowlist with `operations.alerts.generated-subjects`; an empty allowlist cannot start enabled mode, and non-allowlisted subjects are denied. These are test controls, not a substitute for AN-D07 policy or live onboarding. Do not set them for existing accounts/stacks. No contact address, worker/account linkage, realm role or credential is added.

AN-D07 must supply/approve actual purpose/access/retention/deletion for read markers, acknowledgements, receipts, immutable evidence, logs and backups before non-generated account rollout. SIMULATED reports do not anonymize actual account subjects. AN-D08 workload/language/connectivity/staleness budgets, WA/II gates, REAL and M2 remain open. No purge policy is guessed or shipped.

## Storage, locking and rollback

Additive V6 preserves V1–V5, existing rows and old event bytes. New service-owned alert/root/evidence/read/ack/receipt tables have constrained versions/identity/provenance/chronology, immutable evidence and completed receipts, restricted runtime grants and scoped indexes. No schema is applied to the existing developer stack by local packaging/tests.

Source order is existing receipt reservation -> inspection if linked -> incident -> alert -> alert-specific rows. Read/ack reserve their own receipt, lock alert and never acquire source locks afterward. Queries use repeatable-read snapshots and generatedAt. Stale source versions after an old-image interval fail closed rather than serving an active stale alert or silently replaying history.

Application rollback retains V6 evidence; do not down-migrate or delete rows. Disable alerts before any separately authorized rollback. The prior II-1 runtime is exercised against populated V6 alert/read/ack evidence from a hash-checked public source fixture, independent of future owner commits/index state. Its image build omits test sources; runtime readiness/retained business queries and preservation are checked. Historical public runtime inputs are in `docs/alerts-notifications/prior-ii1-manifest.json`; only the changed crop service and release marker are copied as historical text, with prior API bytes reused from the preserved 0.7.0 archive. No private configuration is included.

If source changes while alerts are disabled/rolled back, an existing alert can become stale. Do not auto-enable, drop it, invent a missing revision or backfill. Keep the feature disabled and prepare a separately reviewed reconciliation plan. No recovery writer is shipped. For normal unknown command outcomes, retry the same body/key; 409 requires refreshing facts and explicit user action.

## Checks

Run `mvnw.cmd verify -Pintegration`, then the repository's existing validators plus `python scripts/validate_alert_runtime.py`. That script checks the exact accepted 0.8.0 pin, all 64 actual operations, six actual alert response shapes and privacy allowlists. Existing collection/workforce/crop-health validators retain their release archives. `test_contract_checkout.py` uses disposable indexes only. Docker/browser/platform tests use generated disposable resources, never existing accounts/data.

Architecture records exact results and limits. No new shared event family or broker binding, remote publication, existing-stack deployment, external notification, REAL enablement or AEO change follows from a passing check.

## Delegated provisional privacy calculations — 2026-10-31

Owner subsequently authorized provisional local privacy choices; Architecture `docs/milestones/ALERTS_NOTIFICATIONS_CLIENT_DECISIONS.md` is the single client question/choice register (`AN_PRIVACY_PROVISIONAL_1`). Earlier "no purge policy guessed" refers to the original implementation: provisional choices are now recorded, but no purge/expiry enforcement is shipped. `AlertPrivacyPolicy` computes UTC lifecycle/receipt/log/backup deadlines only; it is not wired into runtime, has no storage/delete/grant/configuration access and does not enable accounts. Eight new tests plus the existing alert domain/input tests pass (33 total). Independent new executable acceptance is still needed for expired inactive reads, receipt/key expiry and history redaction before runtime changes. V1–V6 and accepted 0.8.0 bytes remain unchanged; existing accounts remain disabled and data untouched.
