package com.claire.claims.service;

import com.claire.claims.domain.Claim;
import com.claire.claims.domain.ClaimAppeal;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.DenialReason;
import com.claire.claims.dto.DenialDtos.DeadlineSource;
import com.claire.claims.dto.DenialDtos.DenialReasonResponse;
import com.claire.claims.dto.DenialDtos.DenialWorklistItem;
import com.claire.claims.repository.ClaimAppealRepository;
import com.claire.claims.repository.ClaimRepository;
import com.claire.claims.repository.ClaimStatusHistoryRepository;
import com.claire.claims.repository.DenialReasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The denial worklist: the screen a biller lives in.
 *
 * Returns every claim currently DENIED or under appeal (APPEALED - a denial
 * being appealed is still work in flight), each with its structured denial
 * reasons and the appeal deadline it is racing, sorted by that deadline
 * ascending so the most urgent work is at the top.
 *
 * <h2>The deadline, and what is derived vs stored</h2>
 * The authoritative deadline is the one an {@link ClaimAppeal} froze at filing
 * ({@link DeadlineSource#STAMPED}); it is read here, never recomputed. A claim
 * that has been denied but not yet appealed has no stamped deadline, so this
 * service projects one ({@link DeadlineSource#PROSPECTIVE}) from the denial
 * date plus the payer's appeal window purely to order and prioritise the list.
 * The prospective value is never persisted and never becomes an appeal's
 * deadline - filing stamps its own from the payer window at that moment.
 *
 * {@code daysRemaining} and {@code expired} are likewise derived for display
 * only. A row whose deadline has passed is not hidden: it stays on the list
 * marked expired, because expired denials are exactly what a biller must see.
 *
 * <h2>Tenancy / ownership</h2>
 * This schema has no tenant column (see repo notes): every query is scoped to
 * the claim set it addresses, and read access is governed by RBAC at the
 * controller (the worklist is readable by any authenticated user, VIEWER
 * included). No cross-claim data is reachable.
 *
 * <h2>Open decision surfaced, not guessed</h2>
 * The denial date used for the prospective window is the most recent time the
 * claim entered DENIED (from the append-only status history). Aligning that
 * with a remittance adjudication date, and confirming the default appeal
 * window, are open business decisions flagged for the commander; this service
 * makes the source explicit ({@link DenialWorklistItem#deadlineSource()})
 * rather than presenting a projection as if it were stamped.
 */
@Service
public class DenialWorklistService {

    /** Denials, and denials under appeal, are the work on this list. */
    private static final List<ClaimStatus> WORKLIST_STATUSES =
            List.of(ClaimStatus.DENIED, ClaimStatus.APPEALED);

    private final ClaimRepository claims;
    private final DenialReasonRepository denialReasons;
    private final ClaimAppealRepository appeals;
    private final ClaimStatusHistoryRepository history;

    public DenialWorklistService(ClaimRepository claims,
                                 DenialReasonRepository denialReasons,
                                 ClaimAppealRepository appeals,
                                 ClaimStatusHistoryRepository history) {
        this.claims = claims;
        this.denialReasons = denialReasons;
        this.appeals = appeals;
        this.history = history;
    }

    /**
     * The denial worklist, filtered and sorted by deadline ascending.
     *
     * @param payerId    optional - only this payer's denials
     * @param reasonCode optional - only claims carrying a reason with this CARC
     *                   or RARC code (case-insensitive)
     * @param from       optional - only claims whose service period starts on/after this date
     * @param to         optional - only claims whose service period ends on/before this date
     */
    @Transactional(readOnly = true)
    public List<DenialWorklistItem> worklist(Long payerId, String reasonCode,
                                             LocalDate from, LocalDate to) {
        return buildWorklist(payerId, reasonCode, from, to, LocalDate.now());
    }

    /**
     * The worklist as of a given reference date. Split out so the display-only
     * derivations (days remaining, expired) are deterministic under test; the
     * public entry point supplies today. The codebase reads the clock with
     * {@code LocalDate.now()} rather than an injected Clock bean, so this keeps
     * that convention while staying testable.
     */
    List<DenialWorklistItem> buildWorklist(Long payerId, String reasonCode,
                                           LocalDate from, LocalDate to, LocalDate today) {
        List<Claim> candidates = (payerId == null)
                ? claims.findByStatusIn(WORKLIST_STATUSES)
                : claims.findByStatusInAndPayerId(WORKLIST_STATUSES, payerId);

        // Service-period date-range filter. Kept alongside the reason filter so
        // both narrow the same enriched set; the candidate set (open denials)
        // is bounded, so this is not a hot path.
        List<Claim> inRange = new ArrayList<>();
        for (Claim c : candidates) {
            if (from != null && c.getServiceDateFrom().isBefore(from)) continue;
            if (to != null && c.getServiceDateTo().isAfter(to)) continue;
            inRange.add(c);
        }
        if (inRange.isEmpty()) {
            return List.of();
        }

        List<Long> claimIds = inRange.stream().map(Claim::getId).toList();
        Map<Long, List<DenialReason>> reasonsByClaim = reasonsByClaim(claimIds);
        Map<Long, ClaimAppeal> currentAppealByClaim = currentAppealByClaim(claimIds);
        Map<Long, LocalDate> denialDateByClaim = denialDateByClaim(claimIds);

        String needle = normalizeCode(reasonCode);

        List<DenialWorklistItem> rows = new ArrayList<>();
        for (Claim c : inRange) {
            List<DenialReason> reasons = reasonsByClaim.getOrDefault(c.getId(), List.of());

            // Reason-code filter matches either the CARC or the RARC code.
            if (needle != null && reasons.stream().noneMatch(r -> matchesCode(r, needle))) {
                continue;
            }

            rows.add(toItem(c, reasons, currentAppealByClaim.get(c.getId()),
                            denialDateByClaim.get(c.getId()), today));
        }

        // Most urgent first: soonest deadline at the top. A row always has a
        // deadline (stamped, or projected); the null-safe comparator is defence
        // against a claim with neither an appeal nor a recorded denial date.
        rows.sort(Comparator.comparing(DenialWorklistItem::deadline,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    // ----- enrichment -----------------------------------------------------

    private DenialWorklistItem toItem(Claim c, List<DenialReason> reasons,
                                      ClaimAppeal currentAppeal, LocalDate denialDate,
                                      LocalDate today) {
        LocalDate deadline;
        DeadlineSource source;
        Integer appealLevel;

        if (currentAppeal != null) {
            // Authoritative: the deadline frozen at filing. Never recomputed.
            deadline = currentAppeal.getDeadline();
            source = DeadlineSource.STAMPED;
            appealLevel = currentAppeal.getLevel();
        } else {
            // No appeal filed yet: project the filing window for prioritisation
            // only. Null when the denial date is unknown, so it is not invented.
            deadline = (denialDate == null)
                    ? null
                    : denialDate.plusDays(c.getPayer().getAppealWindowDays());
            source = DeadlineSource.PROSPECTIVE;
            appealLevel = null;
        }

        long daysRemaining = (deadline == null) ? 0L : ChronoUnit.DAYS.between(today, deadline);
        boolean expired = deadline != null && deadline.isBefore(today);

        return new DenialWorklistItem(
                c.getId(),
                c.getClaimNumber(),
                c.getStatus(),
                c.getPatient().getDisplayName(),
                c.getPatient().getMrn(),
                c.getPayer().getId(),
                c.getPayer().getName(),
                c.getProvider().getDisplayName(),
                c.getServiceDateFrom(),
                c.getServiceDateTo(),
                c.getTotalCharge(),
                c.getOutstandingBalance(),
                denialDate,
                deadline,
                source,
                daysRemaining,
                expired,
                appealLevel,
                reasons.stream().map(DenialWorklistService::toReason).toList());
    }

    // ----- batch loads (one query each, no N+1) ---------------------------

    private Map<Long, List<DenialReason>> reasonsByClaim(List<Long> claimIds) {
        Map<Long, List<DenialReason>> byClaim = new HashMap<>();
        for (DenialReason r : denialReasons.findByClaimIdInOrderByClaimIdAscCreatedAtAsc(claimIds)) {
            byClaim.computeIfAbsent(r.getClaim().getId(), k -> new ArrayList<>()).add(r);
        }
        return byClaim;
    }

    /**
     * The current appeal per claim - the highest-level row, which carries the
     * deadline in force (escalations reuse the level-1 deadline). Rows arrive
     * ordered by level ascending, so the last one seen per claim wins.
     */
    private Map<Long, ClaimAppeal> currentAppealByClaim(List<Long> claimIds) {
        Map<Long, ClaimAppeal> byClaim = new HashMap<>();
        for (ClaimAppeal a : appeals.findByClaimIdInOrderByClaimIdAscLevelAsc(claimIds)) {
            byClaim.put(a.getClaim().getId(), a);
        }
        return byClaim;
    }

    private Map<Long, LocalDate> denialDateByClaim(List<Long> claimIds) {
        Map<Long, LocalDate> byClaim = new HashMap<>();
        for (Object[] row : history.latestTransitionInto(ClaimStatus.DENIED, claimIds)) {
            Long claimId = ((Number) row[0]).longValue();
            OffsetDateTime changedAt = (OffsetDateTime) row[1];
            byClaim.put(claimId, changedAt.toLocalDate());
        }
        return byClaim;
    }

    // ----- helpers --------------------------------------------------------

    private static boolean matchesCode(DenialReason r, String needle) {
        return needle.equalsIgnoreCase(r.getCarcCode())
                || (r.getRarcCode() != null && needle.equalsIgnoreCase(r.getRarcCode()));
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) return null;
        return code.trim();
    }

    private static DenialReasonResponse toReason(DenialReason r) {
        return new DenialReasonResponse(
                r.getId(),
                r.getClaim().getId(),
                r.getGroupCode(),
                r.getCarcCode(),
                r.getRarcCode(),
                r.getSource(),
                r.getNote(),
                r.getCreatedBy(),
                r.getCreatedAt());
    }
}
