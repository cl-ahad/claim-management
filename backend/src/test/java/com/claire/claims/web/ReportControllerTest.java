package com.claire.claims.web;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.dto.ReportDtos.*;
import com.claire.claims.service.ReportingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for the reporting endpoint: routing, period-parameter parsing,
 * the JSON shape, and the 400 mapping for a bad period. Security filters are
 * disabled because the endpoint carries no method-level guard (VIEWER-readable);
 * ReportingService is mocked so this test is only about the web layer.
 */
@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReportControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean ReportingService reports;

    private static ReportResponse sampleYear() {
        ReportPeriod period = new ReportPeriod(
                PeriodType.YEAR, 2026, null, "FY 2026",
                OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        return new ReportResponse(
                period, 11, new BigDecimal("1100.00"), new BigDecimal("340.00"),
                5, 3, 2, 1,
                List.of(new StatusGroupCount("approved", 5, new BigDecimal("45.5"))),
                List.of(),
                new ProcessingTime(new BigDecimal("3.0"), 2, 2));
    }

    @Test
    void returnsAnnualReportAsJson() throws Exception {
        when(reports.report(eq(PeriodType.YEAR), eq(2026), isNull())).thenReturn(sampleYear());

        mvc.perform(get("/api/reports/summary").param("period", "year").param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.type").value("YEAR"))
                .andExpect(jsonPath("$.period.label").value("FY 2026"))
                .andExpect(jsonPath("$.claimsFiled").value(11))
                .andExpect(jsonPath("$.approved").value(5))
                .andExpect(jsonPath("$.rejected").value(3))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.voided").value(1))
                .andExpect(jsonPath("$.totalCharged").value(1100.00))
                .andExpect(jsonPath("$.processingTime.averageDays").value(3.0))
                .andExpect(jsonPath("$.processingTime.openClaims").value(2));
    }

    @Test
    void passesQuarterThroughToTheService() throws Exception {
        ReportPeriod period = new ReportPeriod(
                PeriodType.QUARTER, 2026, 2, "Q2 2026",
                OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        ReportResponse resp = new ReportResponse(
                period, 0, new BigDecimal("0.00"), new BigDecimal("0.00"),
                0, 0, 0, 0, List.of(), List.of(),
                new ProcessingTime(new BigDecimal("0.0"), 0, 0));
        when(reports.report(eq(PeriodType.QUARTER), eq(2026), eq(2))).thenReturn(resp);

        mvc.perform(get("/api/reports/summary")
                        .param("period", "quarter").param("year", "2026").param("quarter", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.quarter").value(2))
                .andExpect(jsonPath("$.period.label").value("Q2 2026"));
    }

    @Test
    void rejectsUnknownPeriodWith400() throws Exception {
        mvc.perform(get("/api/reports/summary").param("period", "weekly").param("year", "2026"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void surfacesServiceBusinessRuleAs400() throws Exception {
        when(reports.report(eq(PeriodType.QUARTER), eq(2026), isNull()))
                .thenThrow(new BusinessRuleException("quarter (1-4) is required for a quarterly report"));

        mvc.perform(get("/api/reports/summary").param("period", "quarter").param("year", "2026"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingYearIsARequestError() throws Exception {
        mvc.perform(get("/api/reports/summary").param("period", "year"))
                .andExpect(status().isBadRequest());
    }
}
