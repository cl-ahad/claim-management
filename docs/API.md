# API reference

Base URL `http://localhost:9000/api` (through nginx) or `http://localhost:9090/api` (direct).
Interactive docs: **http://localhost:9000/swagger-ui.html**

Every endpoint except `POST /api/auth/login` requires `Authorization: Bearer <token>`.

---

## Authentication

### `POST /api/auth/login`

```json
{ "username": "biller", "password": "biller123" }
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "username": "biller",
  "fullName": "Bianca Reyes",
  "role": "BILLER",
  "expiresAt": "2026-09-16T01:02:03Z"
}
```

The token is an HS256 JWT carrying `sub`, `roles[]`, `name` and `exp`. Default lifetime 8 hours (`JWT_TTL_MINUTES`).

### `GET /api/auth/me`

Returns `{ username, fullName, role }` for the bearer token.

---

## Claims

### `GET /api/claims`

| Parameter | Type | Notes |
|---|---|---|
| `status` | enum, repeatable | `?status=DRAFT&status=SUBMITTED` |
| `payerId` `patientId` `providerId` | long | exact match |
| `q` | string | claim number, patient last/first name, or MRN |
| `from` `to` | ISO date | filters on the service period |
| `page` `size` | int | defaults 0 and 20 |
| `sort` | string | e.g. `createdAt,desc`, `totalCharge,asc` |

All filters combine with AND.

```json
{
  "content": [
    {
      "id": 10, "claimNumber": "CLM-2026-000010", "status": "DRAFT",
      "patientName": "Castellano, Robert", "patientMrn": "MRN-100001",
      "payerName": "Medicare Part B - Novitas", "providerName": "Haddad, Omar",
      "serviceDateFrom": "2026-09-09", "serviceDateTo": "2026-09-09",
      "totalCharge": 265.00, "paidAmount": 0.00, "outstanding": 265.00,
      "submittedAt": null, "createdAt": "2026-09-09T14:22:01Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 12, "totalPages": 1, "last": true
}
```

### `GET /api/claims/{id}`

Full claim including lines, diagnoses, computed `outstanding`, an `editable` flag and `allowedTransitions` — the list the UI renders its buttons from.

### `GET /api/claims/{id}/history`

The append-only audit trail, oldest first.

### `GET /api/claims/summary`

Dashboard aggregate: total claims, charged, paid, outstanding receivable, and a bucket per status (including empty ones).

### `POST /api/claims` · roles ADMIN, BILLER

```json
{
  "patientId": 1, "providerId": 2, "payerId": 3, "policyId": 1,
  "serviceDateFrom": "2026-09-15", "serviceDateTo": "2026-09-15",
  "placeOfService": "11",
  "notes": "Quarterly diabetic check",
  "diagnoses": [
    { "icd10Code": "E11.9", "description": "Type 2 diabetes mellitus", "sequenceNo": 1 }
  ],
  "lines": [
    { "cptCode": "99214", "modifiers": null, "serviceDate": "2026-09-15",
      "units": 1, "chargeAmount": 245.00, "description": "Office visit, established" }
  ]
}
```

Returns `201` with a `Location` header. The claim is created as `DRAFT`, gets a minted `CLM-YYYY-NNNNNN` number, and `totalCharge` is computed from the lines — any value sent by the client is ignored.

### `PUT /api/claims/{id}` · roles ADMIN, BILLER

Same body. **Only `DRAFT` claims.** Anything else returns `409`. Lines and diagnoses are replaced wholesale.

### `POST /api/claims/{id}/transition` · roles ADMIN, BILLER

```json
{ "targetStatus": "PAID", "reason": "Remittance posted", "allowedAmount": 246.40, "paidAmount": 197.12 }
```

| Target | Requires |
|---|---|
| `SUBMITTED` | ≥1 line, ≥1 diagnosis, charge above zero, coverage active on the service date |
| `REJECTED` `DENIED` `VOID` | `reason` |
| `PAID` | `paidAmount`, not exceeding allowed |
| `PARTIALLY_PAID` | `paidAmount` above zero and below allowed |
| `APPEALED` | Phase 2 — returns `501` |

Returns the updated claim. Illegal transitions return `409` naming what *is* allowed from the current state.

### `DELETE /api/claims/{id}` · role ADMIN

`204`. Only `DRAFT` claims; anything else returns `409` suggesting a void instead, which preserves the audit trail.

### `GET /api/claims/{id}/denial-reasons` · any

The structured reasons recorded against a claim, oldest first. A claim may have several.

```json
[
  {
    "id": 1, "claimId": 4,
    "groupCode": "CO", "carcCode": "197", "rarcCode": "N130",
    "source": "MANUAL", "note": "No prior authorization on file",
    "createdBy": "biller", "createdAt": "2026-09-16T14:02:11Z"
  }
]
```

### `POST /api/claims/{id}/denial-reasons` · roles ADMIN, BILLER

Records one structured denial reason. A reason is an X12 835 Claim Adjustment
triple: a `groupCode` (`CO`/`PR`/`OA`/`PI`) and a `carcCode` are both required,
`rarcCode` is optional. **Free text alone is not a structured reason** — a body
with no `carcCode` returns `400`.

```json
{ "groupCode": "CO", "carcCode": "197", "rarcCode": "N130", "note": "No prior auth" }
```

`source` is optional and defaults to `MANUAL` (this endpoint keys reasons by
hand). Automated `REMITTANCE` reasons are posted by the 835 importer; those take
precedence for display, but a `MANUAL` row is preserved rather than overwritten,
so the audit trail is intact. Returns `201` with a `Location` header.

---

## Reference data

| Method | Path | Roles |
|---|---|---|
| `GET` | `/api/patients?q=&page=&size=&sort=` | any |
| `GET` | `/api/patients/{id}` | any |
| `GET` | `/api/patients/{id}/policies` | any |
| `POST` `PUT` | `/api/patients`, `/api/patients/{id}` | ADMIN, BILLER |
| `GET` | `/api/payers`, `/api/payers/{id}` | any |
| `POST` `PUT` | `/api/payers`, `/api/payers/{id}` | ADMIN |
| `GET` | `/api/providers`, `/api/providers/{id}` | any |
| `POST` `PUT` | `/api/providers`, `/api/providers/{id}` | ADMIN |
| `POST` `PUT` | `/api/policies`, `/api/policies/{id}` | ADMIN, BILLER |

## Lookups

| Path | Returns |
|---|---|
| `/api/reference/status-transitions` | the state machine as data: allowed transitions, descriptions, terminal states |
| `/api/reference/claim-statuses` | each status with `editable`, `terminal`, `open` flags |
| `/api/reference/plan-types` | payer plan type enum |
| `/api/reference/policy-priorities` | `PRIMARY`, `SECONDARY`, `TERTIARY` |
| `/api/reference/places-of-service` | CMS place-of-service codes used by an outpatient practice |

---

## Phase 2 — present, specified, not implemented

All return `501 Not Implemented` with an `X-Phase: 2` header.

| Method | Path | Spec |
|---|---|---|
| `POST` | `/api/edi/837p/export` | [2.1](PHASE2_HANDOFF.md) |
| `POST` | `/api/edi/835/import` | [2.2](PHASE2_HANDOFF.md) |
| `POST` | `/api/eligibility/check` | [2.3](PHASE2_HANDOFF.md) |
| `POST` | `/api/claims/{id}/appeal` | [2.4](PHASE2_HANDOFF.md) |
| `GET` | `/api/denials` | [2.4](PHASE2_HANDOFF.md) |
| `POST` `GET` | `/api/claims/{id}/attachments` | [2.5](PHASE2_HANDOFF.md) |
| `GET` | `/api/analytics/ar-aging` | [2.6](PHASE2_HANDOFF.md) |
| `GET` | `/api/analytics/denial-rate` | [2.6](PHASE2_HANDOFF.md) |
| `GET` | `/api/analytics/payer-performance` | [2.6](PHASE2_HANDOFF.md) |

---

## Errors

Every error is RFC 9457 `application/problem+json`.

```json
{
  "type": "https://claire.example/problems/conflict",
  "title": "Conflict with current state",
  "status": 409,
  "detail": "Cannot move claim CLM-2026-000001 from PAID to SUBMITTED. Allowed from PAID: none - PAID is a terminal state.",
  "instance": "/api/claims/1/transition",
  "timestamp": "2026-09-15T14:02:11Z"
}
```

Validation failures add an `errors` object keyed by field:

```json
{
  "status": 400, "title": "Validation failed",
  "detail": "2 field(s) failed validation",
  "errors": {
    "lines[0].cptCode": "CPT/HCPCS code must be 5 characters, e.g. 99213 or J1885",
    "diagnoses": "a claim needs at least one diagnosis"
  }
}
```

| Status | When |
|---|---|
| `400` | bean validation or a business rule |
| `401` | missing, malformed or expired token |
| `403` | role not permitted for this operation |
| `404` | no such resource |
| `409` | illegal transition, edit to a non-draft, unique constraint, concurrent modification |
| `501` | Phase 2 feature, with `X-Phase: 2` and a `handoffRef` |

---

## Health

| Path | Notes |
|---|---|
| `/actuator/health` | used by the container healthcheck |
| `/actuator/info` | |
| `/actuator/metrics` | requires authentication |
