-- =====================================================================
-- Denial appeals lifecycle (feature 2.4 - denial management & appeals).
--
-- An appeal is opened against a DENIED claim. The filing deadline is stamped
-- once, at filing, from the payer's appeal window and is never recomputed -
-- the window is unforgiving, so a change to the payer configuration must not
-- silently move a claim's deadline.
--
-- payer.appeal_window_days is payer-specific with a documented default (see
-- Payer.DEFAULT_APPEAL_WINDOW_DAYS). Existing payers are backfilled to that
-- default here; the value is an OPEN DECISION pending commander confirmation
-- before production use.
--
-- Escalation runs level 1 (reconsideration) -> 2 (formal) -> 3 (external).
-- Only one appeal may be OPEN per claim at a time; a mistaken filing is
-- WITHDRAWN, never deleted, so the history is intact.
-- =====================================================================

ALTER TABLE payer
    ADD COLUMN appeal_window_days INT NOT NULL DEFAULT 90;

ALTER TABLE payer
    ADD CONSTRAINT ck_payer_appeal_window CHECK (appeal_window_days > 0);

CREATE TABLE claim_appeal (
    id          BIGSERIAL PRIMARY KEY,
    claim_id    BIGINT      NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    level       INT         NOT NULL,
    status      VARCHAR(20) NOT NULL,
    filed_on    DATE        NOT NULL,
    deadline    DATE        NOT NULL,
    narrative   VARCHAR(2000),
    outcome     VARCHAR(20),
    decided_on  DATE,
    filed_by    VARCHAR(60) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_appeal_level   CHECK (level BETWEEN 1 AND 3),
    CONSTRAINT ck_appeal_status  CHECK (status IN ('OPEN', 'CLOSED', 'WITHDRAWN')),
    CONSTRAINT ck_appeal_outcome CHECK (outcome IS NULL
                                        OR outcome IN ('OVERTURNED', 'PARTIAL', 'UPHELD', 'WITHDRAWN'))
);

CREATE INDEX ix_appeal_claim ON claim_appeal (claim_id);
-- At most one OPEN appeal per claim: the "second open appeal => 409" rule is
-- enforced in the service and backstopped here by a partial unique index.
CREATE UNIQUE INDEX ux_appeal_one_open_per_claim
    ON claim_appeal (claim_id) WHERE status = 'OPEN';
-- The worklist sorts by deadline ascending across denied claims.
CREATE INDEX ix_appeal_deadline ON claim_appeal (deadline);

-- Per-payer appeal windows for the seeded payers. These are PLACEHOLDERS that
-- illustrate the value is payer-specific; the production figures are an open
-- decision for the business (see Payer.DEFAULT_APPEAL_WINDOW_DAYS). Any payer
-- not listed keeps the 90-day column default.
UPDATE payer SET appeal_window_days = 180 WHERE payer_code = 'MCARE01';  -- Medicare
UPDATE payer SET appeal_window_days = 60  WHERE payer_code = 'MCAID01';  -- Medicaid

