package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.dto.ReportDtos.*;
import com.claire.claims.repository.ClaimRepository;
import com.claire.claims.repository.ClaimStatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregates claims into quarterly and annual reports: claims filed, the
 * approved / rejected / pending status breakdown, total claim amounts and
 * average processing time. Read-only over existing data; it owns no writes
 * and no schema. Sibling to {@link ClaimService}; controllers stay thin and
 * delegate here.
 *
 * <p>Every period is anchored on {@code claim.created_at}, which is NOT NULL
 * and immutable, so a period's totals are stable and reproducible: re-running
 * last quarter's report always yields the same numbers.
 */
@Service
public class ReportingService {

    /** Reasonable bounds so a typo cannot ask for year 0 or 99999. */
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    /**
     * The status groups reported to the user. Defined once here so JSON and
     * the exports (S-6) map identically. VOID is withdrawn (no decision) and
     * excluded from approved/rejected/pending; it is reported separately so
     * the four groups reconcile to claims filed.
     */
    private static final Set<ClaimStatus> APPROVED =
            EnumSet.of(ClaimStatus.PAID, ClaimStatus.PARTIALLY_PAID, ClaimStatus.ACCEPTED);
    private static final Set<ClaimStatus> REJECTED =
            EnumSet.of(ClaimStatus.REJECTED, ClaimStatus.DENIED);
    private static final Set<ClaimStatus> PENDING =
            EnumSet.of(ClaimStatus.DRAFT, ClaimStatus.SUBMITTED, ClaimStatus.APPEALED);

    /**
     * Statuses that represent a processed/adjudicated claim for the purpose of
     * average processing time. Broader than {@link ClaimStatus#isTerminal()}
     * (which is only PAID/VOID): a claim that was REJECTED, DENIED or
     * PARTIALLY_PAID has been decided even though the workflow may continue.
     */
    private static final List<ClaimStatus> PROCESSING_TERMINALS = List.of(
            ClaimStatus.PAID, ClaimStatus.PARTIALLY_PAID,
            ClaimStatus.DENIED, ClaimStatus.REJECTED, ClaimStatus.VOID);

    private final ClaimRepository claims;
    private final ClaimStatusHistoryRepository history;

    public ReportingService(ClaimRepository claims, ClaimStatusHistoryRepository history) {
        this.claims = claims;
        this.history = history;
    }

    /**
     * Build the report for one period. {@code quarter} is required for a
     * quarterly report (1..4) and ignored for an annual one.
     *
     * @throws BusinessRuleException (400) on a bad or missing period parameter
     */
    @Transactional(readOnly = true)
    public ReportResponse report(PeriodType type, int year, Integer quarter) {
        ReportPeriod period = resolvePeriod(type, year, quarter);

        List<StatusBreakdown> byStatus = aggregateByStatus(period);

        long claimsFiled = 0;
        long approved = 0, rejected = 0, pending = 0, voided = 0;
        BigDecimal totalCharged = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;

        for (StatusBreakdown b : byStatus) {
            claimsFiled += b.count();
            totalCharged = totalCharged.add(b.totalCharge());
            totalPaid = totalPaid.add(b.paidAmount());
            if (APPROVED.contains(b.status())) approved += b.count();
            else if (REJECTED.contains(b.status())) rejected += b.count();
            else if (PENDING.contains(b.status())) pending += b.count();
            else voided += b.count();
        }

        List<StatusGroupCount> groups = List.of(
                new StatusGroupCount("approved", approved, share(approved, claimsFiled)),
                new StatusGroupCount("rejected", rejected, share(rejected, claimsFiled)),
                new StatusGroupCount("pending", pending, share(pending, claimsFiled)),
                new StatusGroupCount("void", voided, share(voided, claimsFiled)));

        ProcessingTime processingTime = averageProcessingTime(period, claimsFiled);

        return new ReportResponse(
                period, claimsFiled, money(totalCharged), money(totalPaid),
                approved, rejected, pending, voided,
                groups, byStatus, processingTime);
    }

    // =====================================================================
    // Period resolution
    // =====================================================================

    /**
     * Turn period parameters into a half-open [from, to) window in UTC. Using a
     * fixed offset (not the server's zone) keeps a period's boundaries stable
     * regardless of where the report is run, matching the reproducibility the
     * approach relies on.
     */
    ReportPeriod resolvePeriod(PeriodType type, int year, Integer quarter) {
        if (type == null) {
            throw new BusinessRuleException("period is required: one of quarter, year");
        }
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new BusinessRuleException(
                    "year must be between " + MIN_YEAR + " and " + MAX_YEAR + ", got " + year);
        }

        if (type == PeriodType.YEAR) {
            OffsetDateTime from = OffsetDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime to = from.plusYears(1);
            return new ReportPeriod(PeriodType.YEAR, year, null, "FY " + year, from, to);
        }

        // QUARTER
        if (quarter == null) {
            throw new BusinessRuleException("quarter (1-4) is required for a quarterly report");
        }
        if (quarter < 1 || quarter > 4) {
            throw new BusinessRuleException("quarter must be between 1 and 4, got " + quarter);
        }
        int startMonth = (quarter - 1) * 3 + 1;
        OffsetDateTime from = OffsetDateTime.of(year, startMonth, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime to = from.plusMonths(3);
        return new ReportPeriod(PeriodType.QUARTER, year, quarter, "Q" + quarter + " " + year, from, to);
    }

    // =====================================================================
    // Aggregation
    // =====================================================================

    private List<StatusBreakdown> aggregateByStatus(ReportPeriod period) {
        List<StatusBreakdown> out = new ArrayList<>();
        for (Object[] row : claims.reportRowsByStatus(period.from(), period.to())) {
            ClaimStatus status = (ClaimStatus) row[0];
            long count = ((Number) row[1]).longValue();
            // COALESCE(SUM(...), 0) comes back as an unpredictable Number
            // subtype depending on the dialect, so never cast it blind.
            BigDecimal charged = money(row[2]);
            BigDecimal paid = money(row[3]);
            out.add(new StatusBreakdown(status, status.getDescription(), count, charged, paid));
        }
        out.sort((a, b) -> a.status().compareTo(b.status()));
        return out;
    }

    /**
     * Average processing time for claims filed in the window that have reached
     * a decision: {@code first-terminal changed_at - created_at}, averaged and
     * reported in days to one decimal. Claims still open have no processing
     * time yet and are excluded from the average; their count is reported as
     * the denominator so the figure is honest.
     */
    private ProcessingTime averageProcessingTime(ReportPeriod period, long claimsFiled) {
        List<Object[]> rows = history.firstTerminalDecisionAt(
                PROCESSING_TERMINALS, period.from(), period.to());

        long decided = 0;
        double totalDays = 0;
        for (Object[] row : rows) {
            OffsetDateTime createdAt = (OffsetDateTime) row[1];
            OffsetDateTime decidedAt = (OffsetDateTime) row[2];
            if (createdAt == null || decidedAt == null) {
                continue;
            }
            Duration d = Duration.between(createdAt, decidedAt);
            // A decision recorded before creation is not physically meaningful;
            // clamp to zero rather than let clock skew produce a negative mean.
            long seconds = Math.max(0, d.getSeconds());
            totalDays += seconds / 86_400.0;
            decided++;
        }

        BigDecimal averageDays = decided == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalDays / decided).setScale(1, RoundingMode.HALF_UP);
        long open = Math.max(0, claimsFiled - decided);
        return new ProcessingTime(averageDays, decided, open);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static BigDecimal share(long part, long total) {
        if (total == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    /** Coerces whatever numeric type the aggregate returned into scaled money. */
    private static BigDecimal money(Object value) {
        if (value == null) return BigDecimal.ZERO;
        BigDecimal d = (value instanceof BigDecimal bd)
                ? bd
                : new BigDecimal(value.toString());
        return d.setScale(2, RoundingMode.HALF_UP);
    }
}
