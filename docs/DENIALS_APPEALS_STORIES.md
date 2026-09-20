# Denial Management & Appeals — Story Proposal (S-2)

**Status: PROPOSED — awaiting commander review. No implementation begins until these are approved.**
Repo: `cl-ahad/claim-management` · Base: `main` · Diagrams: `docs/diagrams/denials-system-{current,target}.png`

Grounded in S-1 recon: `ClaimStatusMachine.apply()` is the only mutator of `claim.status` and returns the `claim_status_history` audit row; `DENIED->APPEALED` currently throws `NotImplementedYetException("...appeal workflow","2.4")`; `APPEALED` routes to `{PAID,DENIED,VOID}` but **not** `PARTIALLY_PAID`; no `denial_reason`/`claim_appeal` tables; `payer.appeal_window_days` does not exist; next Flyway version is **V3**; single-tenant (ownership = `claim.created_by`); RFC 9457 handler maps BusinessRule->400, Conflict->409, AccessDenied->403.

---

## ⚠️ Open decisions for the commander (do NOT proceed until answered)

1. **Default `payer.appeal_window_days`** — no value exists today. A default is required for payers with no explicit window, and it must be documented before production. *Proposed for discussion: 60 days.* **Please confirm the number.**
2. **Denial date definition** — the deadline is `denial_date + appeal_window_days`, stamped on filing and never recomputed. Is `denial_date` the date the claim entered `DENIED` (status-history timestamp), or the **remittance adjudication date** when the denial came from a remittance? These can differ. **Please confirm which date anchors the deadline.**
3. **Verification path** — sandbox has no Java/Maven/Docker and the repo has no CI workflow or backend tests. Implementation will add a GitHub Actions `mvn verify` job with a Postgres service (Testcontainers) and author the acceptance tests fresh. **Please confirm CI-based verification is acceptable.**

---

## Epic: Denial management & appeals

### S-2a — Structured denial reasons
**As** a biller **I want** to record structured denial reasons on a denied claim **so that** appeals and reporting are driven by coded data, not free text.

Scope: `denial_reason` table (V3) + entity/repo/service + `POST /api/claims/{id}/denial-reasons`. Fields: `group_code` (CO/PR/OA/PI), `carc_code` (required), `rarc_code` (optional), `free_text` (optional), `source` (MANUAL|REMITTANCE). A claim may have multiple reasons. REMITTANCE (automated) values win over MANUAL for the same coordinate; MANUAL rows are preserved for audit, never overwritten.

**Acceptance criteria**
- Recording a reason with no structured code (free text only) -> **400**.
- A valid reason requires `group_code` in {CO,PR,OA,PI} and a `carc_code`; `rarc_code` optional -> **201**.
- A claim can carry multiple reasons.
- When a REMITTANCE reason and a MANUAL reason share the same code coordinate, the REMITTANCE value is authoritative for display/logic; the MANUAL row remains queryable for audit.
- Only ADMIN/BILLER may record (`hasAnyRole`); VIEWER write -> **403**.
- Query is scoped to the claim; ownership honoured.

### S-2b — Appeal filing: window, deadline, one-open
**As** a biller **I want** to file an appeal on a denied claim within its payer window **so that** the deadline is fixed and enforced.

Scope: `payer.appeal_window_days` (V3, default per decision #1) + `claim_appeal` table + `AppealService` + `POST /api/claims/{id}/appeals`. Deadline = `denial_date + appeal_window_days`, **stamped on filing, never recomputed**. Filing routes `DENIED->APPEALED` through the state machine (which writes the audit row).

**Acceptance criteria**
- Appeal on a **not-denied** claim -> **409**.
- Appeal with **no structured denial reason** on the claim -> **400**.
- Appeal **after the deadline** -> **400**, and the message names the deadline date.
- A **second open appeal** on the same claim -> **409** (only one open appeal at a time).
- Successful filing transitions `DENIED->APPEALED` and writes exactly one `claim_status_history` row.
- Deadline persisted on the appeal and never changes on re-read.
- ADMIN/BILLER only; VIEWER -> **403**.

### S-2c — Appeal outcomes -> state machine + audit
**As** a biller **I want** to record an appeal outcome **so that** the claim status and audit trail update consistently through the state machine.

Scope: `POST /api/appeals/{id}/outcome`. Outcome->status mapping is applied **via `ClaimStatusMachine`** — outcome writes never set `claim.status` directly. Requires adding the `APPEALED->PARTIALLY_PAID` edge (recon gap). Mappings: OVERTURNED->PAID, PARTIAL->PARTIALLY_PAID, UPHELD->DENIED, WITHDRAWN->DENIED.

**Acceptance criteria**
- OVERTURNED -> claim PAID **and** an audit row written.
- PARTIAL -> claim PARTIALLY_PAID **and** an audit row written (edge `APPEALED->PARTIALLY_PAID` added).
- UPHELD -> claim DENIED + audit row.
- Recording an outcome never mutates `claim.status` outside the state machine (enforced by design/test).
- ADMIN/BILLER only; VIEWER -> **403**.

### S-2d — Escalation levels & withdrawal
**As** a biller **I want** to escalate an upheld appeal or withdraw a mistaken one **so that** the multi-level appeal lifecycle is modelled correctly.

Scope: `level` (1 reconsideration, 2 formal, 3 external) on `claim_appeal`; withdrawal endpoint `POST /api/appeals/{id}/withdraw`.

**Acceptance criteria**
- UPHELD at **level 1** leaves the claim appealable at **level 2**; a new appeal at level 2 is permitted.
- UPHELD at **level 3** ends escalation — no further level can be filed.
- A mistaken appeal is **WITHDRAWN** (state preserved), never deleted.
- WITHDRAWN maps to DENIED via the state machine (claim returns to appealable-if-within-window state, subject to the frozen deadline).
- ADMIN/BILLER only; VIEWER -> **403**.

### S-2e — Denials worklist
**As** a biller **I want** a worklist of denied claims sorted by urgency **so that** I work the closest deadlines first.

Scope: `GET /api/denials` returns denied claims with their reasons, sorted by **deadline ascending**; `days_remaining` computed **for display only** (never persisted, never drives logic). Filters: `payer`, `reason code`, `date range`. Expired claims remain **visible and marked expired**, not hidden.

**Acceptance criteria**
- Returns denied claims with their structured reasons, sorted by deadline ascending.
- `days_remaining` present in the response as a computed display field only.
- Filters by payer, reason code and date range work and compose.
- Expired items are returned with an `expired` marker, not filtered out.
- Read-only endpoint: any authenticated role (incl. VIEWER) can read.

### S-2f — RBAC enforcement across denial/appeal surface
**As** an admin **I want** role rules enforced at the API **so that** VIEWERs cannot mutate denial or appeal data.

Scope: `@PreAuthorize` on every denial/appeal write (`hasAnyRole('ADMIN','BILLER')`); worklist read open to any authenticated user. Verified end to end with `spring-security-test`.

**Acceptance criteria**
- VIEWER on any write (record reason, file appeal, record outcome, withdraw) -> **403**.
- ADMIN and BILLER succeed on all writes.
- VIEWER can read the worklist.
- Enforcement is at the API layer (method security), tested with `@WithMockUser`.

---

## Consolidated acceptance-test matrix (verify in implementation)
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Appeal a not-denied claim | 409 |
| 2 | Record reason with no structured code | 400 |
| 3 | Appeal after deadline | 400 naming the deadline |
| 4 | Second open appeal | 409 |
| 5 | OVERTURNED outcome | claim PAID + audit row |
| 6 | PARTIAL outcome | claim PARTIALLY_PAID + audit row |
| 7 | UPHELD level 1 | still appealable at level 2 |
| 8 | UPHELD level 3 | no further level |
| 9 | VIEWER write | 403 |
