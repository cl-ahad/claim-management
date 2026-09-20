package com.claire.claims.domain;

/**
 * Where a denial reason came from.
 *
 * REMITTANCE - posted automatically from an 835 remittance advice.
 * MANUAL     - keyed by a biller.
 *
 * Automated (REMITTANCE) values win over MANUAL ones, but a MANUAL row is
 * never overwritten or deleted - it is preserved for the audit trail.
 */
public enum DenialSource {
    MANUAL,
    REMITTANCE
}
