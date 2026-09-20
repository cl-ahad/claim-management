package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.common.ApiExceptions.ConflictException;
import com.claire.claims.domain.Claim;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.ClaimStatusHistory;
import com.claire.claims.dto.ClaimDtos.TransitionRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

/**
 * The one place claim status changes. Every rule about what may follow what,
 * what a transition requires, and what it does to the money lives here.
 *
 * Controllers and the UI never decide a transition is legal on their own -
 * they ask this class, or read {@code /api/reference/status-transitions},
 * which is generated from the same enum.
 */
@Component
public class ClaimStatusMachine {

    /**
     * Validates and applies a transition, mutating the claim and returning the
     * audit row to persist. Throws rather than returning a failure code, because
     * an illegal transition is a client error, not a branch the caller handles.
     */
    public ClaimStatusHistory apply(Claim claim, TransitionRequest request, String actor) {
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = request.targetStatus();

        if (from == to) {
            throw new ConflictException("Claim " + claim.getClaimNumber() + " is already " + from);
        }

        if (!from.canTransitionTo(to)) {
            String allowed = from.allowedNext().isEmpty()
                    ? "none - " + from + " is a terminal state"
                    : from.allowedNext().stream().map(Enum::name).collect(Collectors.joining(", "));
            throw new ConflictException(
                    "Cannot move claim " + claim.getClaimNumber() + " from " + from + " to " + to
                    + ". Allowed from " + from + ": " + allowed + ".");
        }

        switch (to) {
            case SUBMITTED      -> onSubmit(claim);
            case REJECTED       -> requireReason(request, "A rejection must record the payer's reason");
            case DENIED         -> requireReason(request, "A denial must record the payer's reason");
            case VOID           -> requireReason(request, "Voiding a claim must record why");
            case DRAFT          -> onReopen(claim);
            case PAID           -> onPaid(claim, request);
            case PARTIALLY_PAID -> onPartiallyPaid(claim, request);
            default             -> { /* ACCEPTED and APPEALED carry no side effects */ }
        }

        claim.setStatus(to);
        return new ClaimStatusHistory(claim, from, to, request.reason(), actor);
    }

    // ----- transition side effects ---------------------------------------

    private void onSubmit(Claim claim) {
        if (claim.getLines().isEmpty()) {
            throw new BusinessRuleException("A claim cannot be submitted without at least one service line");
        }
        if (claim.getDiagnoses().isEmpty()) {
            throw new BusinessRuleException("A claim cannot be submitted without at least one diagnosis");
        }
        if (claim.getTotalCharge().signum() <= 0) {
            throw new BusinessRuleException("A claim cannot be submitted with a zero total charge");
        }
        if (claim.getPolicy() != null && !claim.getPolicy().isActiveOn(claim.getServiceDateFrom())) {
            throw new BusinessRuleException(
                    "Policy " + claim.getPolicy().getMemberId() + " is not active on the service date "
                    + claim.getServiceDateFrom() + ". Attach current coverage before submitting.");
        }
        claim.setSubmittedAt(OffsetDateTime.now());
    }

    /** REJECTED -> DRAFT: the claim goes back to the biller for correction. */
    private void onReopen(Claim claim) {
        claim.setSubmittedAt(null);
        claim.setAllowedAmount(BigDecimal.ZERO);
        claim.setPaidAmount(BigDecimal.ZERO);
        claim.setPatientResponsibility(BigDecimal.ZERO);
    }

    private void onPaid(Claim claim, TransitionRequest request) {
        BigDecimal allowed = resolveAllowed(claim, request);
        BigDecimal paid = require(request.paidAmount(), "paidAmount is required when marking a claim PAID");
        if (paid.compareTo(allowed) > 0) {
            throw new BusinessRuleException(
                    "Paid amount " + paid + " exceeds the allowed amount " + allowed);
        }
        claim.setAllowedAmount(scale(allowed));
        claim.setPaidAmount(scale(paid));
        claim.setPatientResponsibility(scale(allowed.subtract(paid).max(BigDecimal.ZERO)));
    }

    private void onPartiallyPaid(Claim claim, TransitionRequest request) {
        BigDecimal allowed = resolveAllowed(claim, request);
        BigDecimal paid = require(request.paidAmount(),
                "paidAmount is required when marking a claim PARTIALLY_PAID");
        if (paid.signum() <= 0) {
            throw new BusinessRuleException("A partial payment must be greater than zero");
        }
        if (paid.compareTo(allowed) >= 0) {
            throw new BusinessRuleException(
                    "Paid amount " + paid + " covers the full allowed amount " + allowed
                    + ". Use PAID instead of PARTIALLY_PAID.");
        }
        claim.setAllowedAmount(scale(allowed));
        claim.setPaidAmount(scale(paid));
        claim.setPatientResponsibility(scale(allowed.subtract(paid)));
    }

    // ----- helpers --------------------------------------------------------

    private BigDecimal resolveAllowed(Claim claim, TransitionRequest request) {
        if (request.allowedAmount() != null && request.allowedAmount().signum() > 0) {
            return request.allowedAmount();
        }
        if (claim.getAllowedAmount() != null && claim.getAllowedAmount().signum() > 0) {
            return claim.getAllowedAmount();
        }
        return claim.getTotalCharge();
    }

    private BigDecimal require(BigDecimal value, String message) {
        if (value == null) {
            throw new BusinessRuleException(message);
        }
        return value;
    }

    private void requireReason(TransitionRequest request, String message) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BusinessRuleException(message);
        }
    }

    private BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
