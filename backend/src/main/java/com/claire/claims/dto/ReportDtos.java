package com.claire.claims.dto;

import com.claire.claims.domain.ClaimStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read models for the reporting aggregation endpoints.
 *
 * Records in the ClaimDtos style. The aggregation service produces these from
 * windowed claim queries; the money fields are already scaled to 2 dp.
 */
public final class ReportDtos {

    private ReportDtos() { }

    /** The two report shapes the reporting section offers. */
    public enum PeriodType { QUARTERLY, ANNUAL }

    /** One status row within a period's breakdown. Every status appears, including empty ones. */
    public record StatusCount(
            ClaimStatus status,
            String description,
            long count,
            BigDecimal totalCharged,
            BigDecimal totalPaid) { }

    /**
     * One period in a report (a quarter for QUARTERLY, a year for ANNUAL).
     * {@code from} is inclusive, {@code to} is exclusive - a half-open window
     * over claim.createdAt.
     */
    public record PeriodReport(
            String label,
            LocalDate from,
            LocalDate to,
            long claimsCount,
            BigDecimal totalCharged,
            BigDecimal totalPaid,
            List<StatusCount> statusBreakdown) { }

    /**
     * A full report: the type, the year it anchors on, its ordered periods,
     * and the totals across all of them.
     */
    public record ReportResponse(
            PeriodType type,
            int year,
            List<PeriodReport> periods,
            long totalClaims,
            BigDecimal totalCharged,
            BigDecimal totalPaid) { }
}
