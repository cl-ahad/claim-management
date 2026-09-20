package com.claire.claims.domain;

/**
 * Lifecycle of an appeal record, independent of the claim's own status.
 *
 * OPEN      - filed and awaiting a decision. At most one per claim.
 * CLOSED    - a decision was recorded (see {@link AppealOutcome}).
 * WITHDRAWN - filed in error and retracted. The row is kept, not deleted,
 *             so the audit trail survives.
 */
public enum AppealStatus {
    OPEN,
    CLOSED,
    WITHDRAWN
}
