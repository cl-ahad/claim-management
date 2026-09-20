-- =====================================================================
-- Structured denial reasons (feature 2.4 - denial management).
--
-- A denied claim carries one or more structured reasons, each an X12 835
-- Claim Adjustment triple: a group code (CO/PR/OA/PI), a CARC reason code,
-- and an optional RARC remark code. Free text alone is never sufficient -
-- the API rejects a reason with no structured code.
--
-- source records where the reason came from: REMITTANCE (posted from an 835,
-- automated) or MANUAL (keyed by a biller). Automated values win; manual
-- rows are preserved rather than overwritten, so the audit trail is intact.
-- =====================================================================

CREATE TABLE denial_reason (
    id          BIGSERIAL PRIMARY KEY,
    claim_id    BIGINT      NOT NULL REFERENCES claim (id) ON DELETE CASCADE,
    group_code  VARCHAR(2)  NOT NULL,
    carc_code   VARCHAR(10) NOT NULL,
    rarc_code   VARCHAR(10),
    source      VARCHAR(20) NOT NULL,
    note        VARCHAR(500),
    created_by  VARCHAR(60) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_denial_group_code CHECK (group_code IN ('CO', 'PR', 'OA', 'PI')),
    CONSTRAINT ck_denial_source     CHECK (source IN ('MANUAL', 'REMITTANCE'))
);

CREATE INDEX ix_denial_reason_claim ON denial_reason (claim_id);
-- Worklist filters and analytics group by the CARC reason code.
CREATE INDEX ix_denial_reason_carc  ON denial_reason (carc_code);
