package com.claire.claims.domain;

/**
 * X12 835 Claim Adjustment Group Code - the "who is responsible" bucket
 * that every structured denial reason carries.
 *
 * CO - Contractual Obligation
 * PR - Patient Responsibility
 * OA - Other Adjustment
 * PI - Payer Initiated Reduction
 */
public enum DenialGroupCode {
    CO,
    PR,
    OA,
    PI
}
