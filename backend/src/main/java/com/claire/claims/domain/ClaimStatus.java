package com.claire.claims.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Claim lifecycle states and the legal transitions between them.
 *
 * This enum is the single source of truth for the workflow. The service layer,
 * the /api/reference/status-transitions endpoint and the React UI all read
 * from here, so the rules cannot drift between layers.
 *
 * APPEALED is reached from DENIED when an appeal is filed (feature 2.4). The
 * appeal preconditions - a structured denial reason and an unexpired deadline -
 * are enforced by AppealService before it drives this transition; the machine
 * owns only the legality of the transition and the audit row it writes.
 */
public enum ClaimStatus {

    DRAFT("Created, still editable"),
    SUBMITTED("Sent to the payer, awaiting acknowledgement"),
    ACCEPTED("Acknowledged by the payer, in adjudication"),
    REJECTED("Rejected before adjudication, correct and resubmit"),
    PARTIALLY_PAID("Some lines paid, balance outstanding"),
    PAID("Paid in full"),
    DENIED("Adjudicated and denied"),
    APPEALED("Denial under appeal (Phase 2)"),
    VOID("Withdrawn, no further processing");

    private final String description;

    ClaimStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    private static final Map<ClaimStatus, Set<ClaimStatus>> TRANSITIONS =
            new EnumMap<>(ClaimStatus.class);

    static {
        TRANSITIONS.put(DRAFT,          EnumSet.of(SUBMITTED, VOID));
        TRANSITIONS.put(SUBMITTED,      EnumSet.of(ACCEPTED, REJECTED, VOID));
        TRANSITIONS.put(REJECTED,       EnumSet.of(DRAFT, VOID));
        TRANSITIONS.put(ACCEPTED,       EnumSet.of(PAID, PARTIALLY_PAID, DENIED, VOID));
        TRANSITIONS.put(PARTIALLY_PAID, EnumSet.of(PAID, DENIED, VOID));
        // A denial may be appealed (feature 2.4) or voided.
        TRANSITIONS.put(DENIED,         EnumSet.of(APPEALED, VOID));
        // An appeal resolves to paid, partially paid, back to denied, or void.
        TRANSITIONS.put(APPEALED,       EnumSet.of(PAID, PARTIALLY_PAID, DENIED, VOID));
        TRANSITIONS.put(PAID,           EnumSet.noneOf(ClaimStatus.class));
        TRANSITIONS.put(VOID,           EnumSet.noneOf(ClaimStatus.class));
    }

    public Set<ClaimStatus> allowedNext() {
        return Collections.unmodifiableSet(
                TRANSITIONS.getOrDefault(this, EnumSet.noneOf(ClaimStatus.class)));
    }

    public boolean canTransitionTo(ClaimStatus target) {
        return allowedNext().contains(target);
    }

    /** Only DRAFT claims may have their content changed. */
    public boolean isEditable() {
        return this == DRAFT;
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** Counts toward accounts receivable. */
    public boolean isOpen() {
        return this != PAID && this != VOID;
    }
}
