package com.claire.claims.dto;

import com.claire.claims.domain.AppealOutcome;
import com.claire.claims.domain.AppealStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class AppealDtos {

    private AppealDtos() { }

    // ----- write models ---------------------------------------------------

    /**
     * Files an appeal on a denied claim. The escalation level is optional and
     * defaults to 1 (reconsideration); levels 2 (formal) and 3 (external) are
     * reached after an UPHELD decision at the level below. The deadline is not
     * supplied by the caller - it is stamped from the payer's appeal window at
     * filing and never recomputed.
     */
    public record FileAppealRequest(
            Integer level,
            @Size(max = 2000) String narrative) { }

    /**
     * Records the decision on an open appeal. WITHDRAWN retracts a mistaken
     * filing; the record is kept, not deleted. Every outcome drives the
     * resulting claim status through the state machine.
     *
     * allowedAmount and paidAmount feed the money side effects of the resulting
     * status change: OVERTURNED settles the claim in full (defaulting paid to
     * the allowed amount when not supplied), PARTIAL requires a paidAmount, and
     * UPHELD/WITHDRAWN carry no money. They are ignored for outcomes that need
     * no payment.
     */
    public record RecordOutcomeRequest(
            @NotNull(message = "outcome is required (OVERTURNED, PARTIAL, UPHELD or WITHDRAWN)")
            AppealOutcome outcome,
            @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal allowedAmount,
            @DecimalMin(value = "0.00") @Digits(integer = 10, fraction = 2) BigDecimal paidAmount,
            @Size(max = 2000) String note) { }

    // ----- read model -----------------------------------------------------

    public record AppealResponse(
            Long id,
            Long claimId,
            int level,
            AppealStatus status,
            LocalDate filedOn,
            LocalDate deadline,
            String narrative,
            AppealOutcome outcome,
            LocalDate decidedOn,
            String filedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }
}
