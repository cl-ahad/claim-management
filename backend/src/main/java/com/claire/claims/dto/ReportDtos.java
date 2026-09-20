package com.claire.claims.dto;

import com.claire.claims.domain.ClaimStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read models for the reporting feature (quarterly & annual claims reports).
 * Java records in the same style as {@link ClaimDtos}. Reporting is read-only,
 * so there are no write models here.
 */
public final class ReportDtos {

    private ReportDtos() { }

    /** Which kind of period a report covers. */
    public enum PeriodType { QUARTER, YEAR }

    /**
     * The resolved reporting window. {@code from} is inclusive and {@code to}
     * exclusive (half-open), so a claim's created_at belongs to exactly one
     * period. {@code quarter} is null for an annual report.
     */
    public record ReportPeriod(
            PeriodType type,
            int year,
            Integer quarter,
            String label,
            OffsetDateTime from,
            OffsetDateTime to) { }

    /**
     * Count of claims in one reported status group (approved / rejected /
     * pending / void), with the share of claims filed it represents.
     */
    public record StatusGroupCount(
            String group,
            long count,
            BigDecimal share) { }

    /** Per-status count and money totals, so the group mapping is auditable. */
    public record StatusBreakdown(
            ClaimStatus status,
            String description,
            long count,
            BigDecimal totalCharge,
            BigDecimal paidAmount) { }

    /**
     * The average processing time metric, in days to one decimal, together
     * with the denominator it was averaged over so the number is honest.
     */
    public record ProcessingTime(
            BigDecimal averageDays,
            long decidedClaims,
            long openClaims) { }

    /**
     * The full report for one period: claims filed, the approved/rejected/
     * pending breakdown, total claim amounts, and average processing time.
     */
    public record ReportResponse(
            ReportPeriod period,
            long claimsFiled,
            BigDecimal totalCharged,
            BigDecimal totalPaid,
            long approved,
            long rejected,
            long pending,
            long voided,
            List<StatusGroupCount> statusGroups,
            List<StatusBreakdown> byStatus,
            ProcessingTime processingTime) { }
}
