package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.common.ApiExceptions.ConflictException;
import com.claire.claims.common.ApiExceptions.NotFoundException;
import com.claire.claims.domain.AppealOutcome;
import com.claire.claims.domain.AppealStatus;
import com.claire.claims.domain.Claim;
import com.claire.claims.domain.ClaimAppeal;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.dto.AppealDtos.AppealResponse;
import com.claire.claims.dto.AppealDtos.FileAppealRequest;
import com.claire.claims.dto.AppealDtos.RecordOutcomeRequest;
import com.claire.claims.dto.ClaimDtos.TransitionRequest;
import com.claire.claims.repository.ClaimAppealRepository;
import com.claire.claims.repository.ClaimRepository;
import com.claire.claims.repository.DenialReasonRepository;
import com.claire.claims.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The appeals lifecycle on a denied claim.
 *
 * Every rule about when an appeal may be filed, escalated, withdrawn or
 * decided lives here. Two invariants shape the design:
 *
 *  1. The filing deadline is stamped once, at the FIRST filing, from the
 *     payer's appeal window, and is carried forward to escalations - it is
 *     never recomputed, because the window is unforgiving.
 *  2. This service never sets the claim status directly. It records the
 *     appeal, then drives the resulting status change through
 *     {@link ClaimService#transition}, so the state machine writes the audit
 *     row for every status change.
 *
 * Queries are scoped to the addressed claim id; this schema has no tenant
 * column, so claim-scoping plus method-level RBAC is how ownership is
 * enforced (see repo notes on tenancy).
 */
@Service
public class AppealService {

    private final ClaimRepository claims;
    private final ClaimAppealRepository appeals;
    private final DenialReasonRepository denialReasons;
    private final ClaimService claimService;
    private final CurrentUserProvider currentUser;

    public AppealService(ClaimRepository claims,
                         ClaimAppealRepository appeals,
                         DenialReasonRepository denialReasons,
                         ClaimService claimService,
                         CurrentUserProvider currentUser) {
        this.claims = claims;
        this.appeals = appeals;
        this.denialReasons = denialReasons;
        this.claimService = claimService;
        this.currentUser = currentUser;
    }

    /**
     * Files an appeal on a denied claim and drives DENIED -&gt; APPEALED.
     *
     * @throws NotFoundException     the claim does not exist
     * @throws ConflictException     the claim is not denied, or an appeal is already open
     * @throws BusinessRuleException there is no structured denial reason, the level is
     *                               invalid, the previous level was not upheld, or the
     *                               deadline has already passed (the message names it)
     */
    @Transactional
    public AppealResponse file(Long claimId, FileAppealRequest request) {
        Claim claim = claims.findById(claimId)
                .orElseThrow(() -> new NotFoundException("Claim", claimId));

        // Only a denied claim can be appealed. A not-denied claim is a conflict
        // with current state, not a bad request.
        if (claim.getStatus() != ClaimStatus.DENIED) {
            throw new ConflictException(
                    "Claim " + claim.getClaimNumber() + " is " + claim.getStatus()
                    + " and cannot be appealed; only a DENIED claim can be appealed.");
        }

        // A structured denial reason must exist - an appeal argues against a
        // coded reason, not free text.
        if (!denialReasons.existsByClaimId(claimId)) {
            throw new BusinessRuleException(
                    "Claim " + claim.getClaimNumber() + " has no structured denial reason recorded; "
                    + "record a denial reason before filing an appeal.");
        }

        // Only one appeal may be open at a time.
        if (appeals.existsByClaimIdAndStatus(claimId, AppealStatus.OPEN)) {
            throw new ConflictException(
                    "Claim " + claim.getClaimNumber() + " already has an open appeal; "
                    + "decide or withdraw it before filing another.");
        }

        int level = request.level() == null ? 1 : request.level();
        if (level < 1 || level > 3) {
            throw new BusinessRuleException(
                    "Appeal level must be 1 (reconsideration), 2 (formal) or 3 (external), not " + level + ".");
        }

        // The deadline is stamped at the first filing and carried forward.
        // Escalations reuse it; it is never recomputed from the payer window.
        LocalDate deadline = resolveDeadlineForLevel(claim, claimId, level);

        // The window is unforgiving: filing after the deadline is rejected, and
        // the message names the deadline the caller missed.
        LocalDate today = LocalDate.now();
        if (today.isAfter(deadline)) {
            throw new BusinessRuleException(
                    "The appeal deadline for claim " + claim.getClaimNumber() + " was " + deadline
                    + " and has passed; an appeal can no longer be filed.");
        }

        ClaimAppeal appeal = new ClaimAppeal(
                claim, level, today, deadline, request.narrative(), currentUser.username());
        ClaimAppeal saved = appeals.save(appeal);

        // Drive the status change THROUGH the state machine so the audit row is
        // written. The claim is already APPEALED after the first filing; an
        // escalation of an already-appealed claim needs no further transition.
        if (claim.getStatus() == ClaimStatus.DENIED) {
            claimService.transition(claimId, new TransitionRequest(
                    ClaimStatus.APPEALED,
                    "Appeal filed (level " + level + "), deadline " + deadline,
                    null, null));
        }

        return toResponse(saved);
    }

    /**
     * Records the decision on the open appeal and drives the resulting claim
     * status change through the state machine. WITHDRAWN retracts a mistaken
     * filing without deleting the record.
     *
     * @throws NotFoundException     the claim does not exist
     * @throws ConflictException     there is no open appeal on the claim
     */
    @Transactional
    public AppealResponse recordOutcome(Long claimId, RecordOutcomeRequest request) {
        Claim claim = claims.findById(claimId)
                .orElseThrow(() -> new NotFoundException("Claim", claimId));

        ClaimAppeal appeal = appeals.findByClaimIdAndStatus(claimId, AppealStatus.OPEN)
                .orElseThrow(() -> new ConflictException(
                        "Claim " + claim.getClaimNumber() + " has no open appeal to decide."));

        AppealOutcome outcome = request.outcome();

        // Record the decision on the appeal row. A withdrawal is retracted, not
        // decided - the row is kept for audit either way.
        appeal.setOutcome(outcome);
        appeal.setDecidedOn(LocalDate.now());
        appeal.setStatus(outcome == AppealOutcome.WITHDRAWN
                ? AppealStatus.WITHDRAWN
                : AppealStatus.CLOSED);
        if (request.note() != null && !request.note().isBlank()) {
            appeal.setNarrative(request.note());
        }
        appeals.save(appeal);

        // The status change is driven through the state machine, which writes
        // the audit row. The outcome write above never touched claim.status.
        ClaimStatus target = outcome.targetStatus();
        String reason = "Appeal (level " + appeal.getLevel() + ") outcome " + outcome
                + " -> " + target;
        claimService.transition(claimId, transitionFor(outcome, claim, request, reason));

        return toResponse(appeal);
    }

    /** Every appeal recorded against a claim, oldest level first, scoped to the claim. */
    @Transactional(readOnly = true)
    public List<AppealResponse> appealsFor(Long claimId) {
        if (!claims.existsById(claimId)) {
            throw new NotFoundException("Claim", claimId);
        }
        return appeals.findByClaimIdOrderByLevelAscFiledOnAsc(claimId).stream()
                .map(AppealService::toResponse)
                .toList();
    }

    // ----- internals ------------------------------------------------------

    /**
     * The deadline for an appeal at the requested level. Level 1 stamps it from
     * the payer window; a higher level reuses the deadline stamped on the
     * highest prior appeal (never recomputed) and requires that prior level to
     * have been UPHELD - and, at level 3, that no external appeal has already
     * concluded escalation.
     */
    private LocalDate resolveDeadlineForLevel(Claim claim, Long claimId, int level) {
        List<ClaimAppeal> prior = appeals.findByClaimIdOrderByLevelAscFiledOnAsc(claimId);

        if (level == 1) {
            if (!prior.isEmpty()) {
                throw new ConflictException(
                        "Claim " + claim.getClaimNumber() + " has already been appealed at level 1.");
            }
            int window = claim.getPayer().getAppealWindowDays();
            return LocalDate.now().plusDays(window);
        }

        // Escalation: the level below must exist and have been UPHELD.
        Optional<ClaimAppeal> below = prior.stream()
                .filter(a -> a.getLevel() == level - 1)
                .reduce((first, second) -> second);
        if (below.isEmpty()) {
            throw new BusinessRuleException(
                    "Cannot file a level " + level + " appeal on claim " + claim.getClaimNumber()
                    + " without a level " + (level - 1) + " appeal first.");
        }
        if (below.get().getOutcome() != AppealOutcome.UPHELD) {
            throw new BusinessRuleException(
                    "A level " + level + " appeal is only allowed after the level " + (level - 1)
                    + " appeal was UPHELD; it was "
                    + below.get().getOutcome() + ".");
        }
        // The stamped deadline is carried forward, never recomputed.
        return below.get().getDeadline();
    }

    /**
     * Builds the transition for an outcome, supplying the money the resulting
     * status change needs. OVERTURNED settles in full (paid defaults to the
     * allowed amount, which itself defaults to the claim's charge via the state
     * machine); PARTIAL carries the partial payment; UPHELD and WITHDRAWN move
     * the claim back to DENIED and carry no money.
     */
    private static TransitionRequest transitionFor(AppealOutcome outcome, Claim claim,
                                                    RecordOutcomeRequest request, String reason) {
        ClaimStatus target = outcome.targetStatus();
        return switch (outcome) {
            case OVERTURNED -> {
                BigDecimal allowed = firstPositive(request.allowedAmount(),
                        claim.getAllowedAmount(), claim.getTotalCharge());
                BigDecimal paid = request.paidAmount() != null ? request.paidAmount() : allowed;
                yield new TransitionRequest(target, reason, paid, allowed);
            }
            case PARTIAL -> new TransitionRequest(
                    target, reason, request.paidAmount(), request.allowedAmount());
            case UPHELD, WITHDRAWN -> new TransitionRequest(target, reason, null, null);
        };
    }

    /** First non-null, strictly positive amount in the list, or ZERO. */
    private static BigDecimal firstPositive(BigDecimal... candidates) {
        for (BigDecimal c : candidates) {
            if (c != null && c.signum() > 0) {
                return c;
            }
        }
        return BigDecimal.ZERO;
    }

    private static AppealResponse toResponse(ClaimAppeal a) {
        return new AppealResponse(
                a.getId(),
                a.getClaim().getId(),
                a.getLevel(),
                a.getStatus(),
                a.getFiledOn(),
                a.getDeadline(),
                a.getNarrative(),
                a.getOutcome(),
                a.getDecidedOn(),
                a.getFiledBy(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
