package com.claire.claims.web;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.dto.ReportDtos.PeriodType;
import com.claire.claims.dto.ReportDtos.ReportResponse;
import com.claire.claims.service.ReportingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only reporting endpoints. Sibling to {@link ClaimController}. Like the
 * GET endpoints there, these carry no {@code @PreAuthorize}: reports are
 * read-only, so a VIEWER may read them (only writes are role-guarded).
 * The controller stays thin and delegates all aggregation to
 * {@link ReportingService}.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportingService reports;

    public ReportController(ReportingService reports) {
        this.reports = reports;
    }

    /**
     * Quarterly or annual claims report as JSON.
     *
     * @param period "quarter" or "year"
     * @param year   the calendar year, e.g. 2026
     * @param quarter 1-4, required when period=quarter, ignored when period=year
     */
    @GetMapping("/summary")
    public ReportResponse summary(@RequestParam String period,
                                  @RequestParam int year,
                                  @RequestParam(required = false) Integer quarter) {
        return reports.report(parsePeriod(period), year, quarter);
    }

    private static PeriodType parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new BusinessRuleException("period is required: one of quarter, year");
        }
        return switch (period.trim().toLowerCase()) {
            case "quarter", "quarterly" -> PeriodType.QUARTER;
            case "year", "annual", "yearly" -> PeriodType.YEAR;
            default -> throw new BusinessRuleException(
                    "period must be one of quarter, year; got '" + period + "'");
        };
    }
}
