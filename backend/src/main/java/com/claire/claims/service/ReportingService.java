package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.dto.ReportDtos.*;
import com.claire.claims.repository.ClaimRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates claims into reporting periods.
 *
 * Sibling to {@link ClaimService}: read-only, no migration, and anchored on
 * claim.createdAt. Each period is a half-open [from, to) window; the service
 * builds those windows from a year (and, for quarterly, a quarter) and asks
 * the repository for per-status counts and money totals within them. All
 * period arithmetic lives here so it stays unit-testable without a database.
 */
@Service
public class ReportingService {

    /** Guards against absurd year inputs while still covering any realistic claim history. */
    private static final int MIN_YEAR = 2000;

    /** How many years an annual report covers by default, ending at the requested year. */
    static final int DEFAULT_ANNUAL_SPAN = 5;
    private static final int MAX_ANNUAL_SPAN = 20;

    private final ClaimRepository claims;

    public ReportingService(ClaimRepository claims) {
        this.claims = claims;
    }

    /**
     * Quarterly report: the four quarters of {@code year}, each with its claims
     * count, money totals and per-status breakdown.
     */
    @Transactional(readOnly = true)
    public ReportResponse quarterly(int year) {
        int maxYear = Year.now(ZoneOffset.UTC).getValue();
        requireYear(year, maxYear);

        List<PeriodReport> periods = new ArrayList<>(4);
        for (int q = 1; q <= 4; q++) {
            LocalDate from = firstDayOfQuarter(year, q);
            LocalDate to = from.plusMonths(3);
            periods.add(periodReport("Q" + q + " " + year, from, to));
        }
        return combine(PeriodType.QUARTERLY, year, periods);
    }

    /**
     * Annual report: {@code span} whole years ending at (and including)
     * {@code year}, each with its claims count, money totals and per-status
     * breakdown.
     */
    @Transactional(readOnly = true)
    public ReportResponse annual(int year, int span) {
        int maxYear = Year.now(ZoneOffset.UTC).getValue();
        requireYear(year, maxYear);
        if (span < 1 || span > MAX_ANNUAL_SPAN) {
            throw new BusinessRuleException(
                    "years must be between 1 and " + MAX_ANNUAL_SPAN + " (got " + span + ")");
        }

        List<PeriodReport> periods = new ArrayList<>(span);
        int firstYear = year - span + 1;
        for (int y = firstYear; y <= year; y++) {
            LocalDate from = LocalDate.of(y, 1, 1);
            LocalDate to = from.plusYears(1);
            periods.add(periodReport(String.valueOf(y), from, to));
        }
        return combine(PeriodType.ANNUAL, year, periods);
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private void requireYear(int year, int maxYear) {
        if (year < MIN_YEAR || year > maxYear) {
            throw new BusinessRuleException(
                    "year must be between " + MIN_YEAR + " and " + maxYear + " (got " + year + ")");
        }
    }

    private static LocalDate firstDayOfQuarter(int year, int quarter) {
        return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
    }

    /** Aggregates one [from, to) window into a fully-populated period report. */
    private PeriodReport periodReport(String label, LocalDate from, LocalDate to) {
        OffsetDateTime fromTs = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toTs = to.atStartOfDay().atOffset(ZoneOffset.UTC);

        Map<ClaimStatus, StatusCount> byStatus = new EnumMap<>(ClaimStatus.class);
        long count = 0;
        BigDecimal charged = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;

        for (Object[] row : claims.reportRowsByStatus(fromTs, toTs)) {
            ClaimStatus status = (ClaimStatus) row[0];
            long c = ((Number) row[1]).longValue();
            BigDecimal rowCharged = money(row[2]);
            BigDecimal rowPaid = money(row[3]);

            byStatus.put(status, new StatusCount(status, status.getDescription(), c, rowCharged, rowPaid));
            count += c;
            charged = charged.add(rowCharged);
            paid = paid.add(rowPaid);
        }

        // Every status appears, including the empty ones, so a chart never
        // silently drops a bucket when a period has nothing in it.
        List<StatusCount> breakdown = new ArrayList<>(ClaimStatus.values().length);
        for (ClaimStatus status : ClaimStatus.values()) {
            breakdown.add(byStatus.getOrDefault(status,
                    new StatusCount(status, status.getDescription(), 0,
                                    BigDecimal.ZERO, BigDecimal.ZERO)));
        }

        return new PeriodReport(label, from, to, count, money(charged), money(paid), breakdown);
    }

    private ReportResponse combine(PeriodType type, int year, List<PeriodReport> periods) {
        long totalClaims = 0;
        BigDecimal totalCharged = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        for (PeriodReport p : periods) {
            totalClaims += p.claimsCount();
            totalCharged = totalCharged.add(p.totalCharged());
            totalPaid = totalPaid.add(p.totalPaid());
        }
        return new ReportResponse(type, year, periods,
                totalClaims, money(totalCharged), money(totalPaid));
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
