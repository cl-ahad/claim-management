package com.claire.claims.domain;

/**
 * The decision recorded on an appeal, and the claim status each maps to.
 *
 * The outcome and the claim status are deliberately separate concerns: an
 * outcome is recorded on the appeal row, and the resulting status change is
 * driven THROUGH {@link ClaimStatusMachine} so the audit row is always
 * written. An outcome never sets the claim status directly.
 *
 * Mappings (directive 2.4): OVERTURNED -> PAID, PARTIAL -> PARTIALLY_PAID,
 * UPHELD -> DENIED, WITHDRAWN -> DENIED.
 */
public enum AppealOutcome {

    OVERTURNED(ClaimStatus.PAID),
    PARTIAL(ClaimStatus.PARTIALLY_PAID),
    UPHELD(ClaimStatus.DENIED),
    WITHDRAWN(ClaimStatus.DENIED);

    private final ClaimStatus targetStatus;

    AppealOutcome(ClaimStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    /** The claim status this outcome resolves the appeal to. */
    public ClaimStatus targetStatus() {
        return targetStatus;
    }
}
