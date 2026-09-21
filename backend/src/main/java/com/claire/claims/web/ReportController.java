package com.claire.claims.web;

import com.claire.claims.dto.ReportDtos.ReportResponse;
import com.claire.claims.service.ReportingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Year;
import java.time.ZoneOffset;

/**
 * Reporting aggregation endpoints, sibling to {@link ClaimController}.
 *
 * Reads only: like the claim GETs, these carry no @PreAuthorize because any
 * authenticated role (including VIEWER) may read reports. All aggregation lives
 * in {@link ReportingService}; this controller only binds request parameters.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportingService reports;

    public ReportController(ReportingService reports) {
        this.reports = reports;
    }

    /**
     * Quarterly report: the four quarters of {@code year} (defaults to the
     * current year), each with claims count, money totals and status breakdown.
     */
    @GetMapping("/quarterly")
    public ReportResponse quarterly(
            @RequestParam(required = false) Integer year) {
        return reports.quarterly(year != null ? year : currentYear());
    }

    /**
     * Annual report: {@code years} whole years ending at {@code year}
     * (defaults to the current year), each with claims count, money totals
     * and status breakdown.
     */
    @GetMapping("/annual")
    public ReportResponse annual(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "5") int years) {
        return reports.annual(year != null ? year : currentYear(), years);
    }

    private static int currentYear() {
        return Year.now(ZoneOffset.UTC).getValue();
    }
}
