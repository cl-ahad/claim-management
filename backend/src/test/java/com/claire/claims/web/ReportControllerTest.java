package com.claire.claims.web;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.common.GlobalExceptionHandler;
import com.claire.claims.dto.ReportDtos.PeriodReport;
import com.claire.claims.dto.ReportDtos.PeriodType;
import com.claire.claims.dto.ReportDtos.ReportResponse;
import com.claire.claims.service.ReportExporter;
import com.claire.claims.service.ReportingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pins routing and error mapping for the reporting endpoints. Security filters
 * are disabled (the reads carry no @PreAuthorize) and the service is mocked, so
 * this test is about the HTTP contract, not the aggregation math.
 */
@WebMvcTest(ReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ReportControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReportingService reports;

    @MockitoBean
    ReportExporter exporter;

    private static ReportResponse sampleQuarterly(int year) {
        PeriodReport q1 = new PeriodReport(
                "Q1 " + year, LocalDate.of(year, 1, 1), LocalDate.of(year, 4, 1),
                2, new BigDecimal("200.00"), new BigDecimal("100.00"), List.of());
        return new ReportResponse(PeriodType.QUARTERLY, year, List.of(q1),
                2, new BigDecimal("200.00"), new BigDecimal("100.00"));
    }

    @Test
    void quarterly_returnsReportForRequestedYear() throws Exception {
        when(reports.quarterly(2025)).thenReturn(sampleQuarterly(2025));

        mvc.perform(get("/api/reports/quarterly").param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("QUARTERLY"))
                .andExpect(jsonPath("$.year").value(2025))
                .andExpect(jsonPath("$.periods[0].label").value("Q1 2025"))
                .andExpect(jsonPath("$.periods[0].claimsCount").value(2))
                .andExpect(jsonPath("$.totalCharged").value(200.00));
    }

    @Test
    void quarterly_defaultsToCurrentYearWhenYearOmitted() throws Exception {
        int currentYear = Year.now(ZoneOffset.UTC).getValue();
        when(reports.quarterly(currentYear)).thenReturn(sampleQuarterly(currentYear));

        mvc.perform(get("/api/reports/quarterly"))
                .andExpect(status().isOk());

        verify(reports).quarterly(currentYear);
    }

    @Test
    void annual_passesTheYearAndSpanThrough() throws Exception {
        ReportResponse resp = new ReportResponse(PeriodType.ANNUAL, 2024, List.of(),
                0, BigDecimal.ZERO, BigDecimal.ZERO);
        when(reports.annual(2024, 3)).thenReturn(resp);

        mvc.perform(get("/api/reports/annual").param("year", "2024").param("years", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ANNUAL"));

        verify(reports).annual(2024, 3);
    }

    @Test
    void annual_defaultsSpanToFive() throws Exception {
        int currentYear = Year.now(ZoneOffset.UTC).getValue();
        ReportResponse resp = new ReportResponse(PeriodType.ANNUAL, currentYear, List.of(),
                0, BigDecimal.ZERO, BigDecimal.ZERO);
        when(reports.annual(eq(currentYear), anyInt())).thenReturn(resp);

        mvc.perform(get("/api/reports/annual"))
                .andExpect(status().isOk());

        verify(reports).annual(currentYear, 5);
    }

    @Test
    void badPeriodInputMapsToProblemJson400() throws Exception {
        when(reports.quarterly(1999))
                .thenThrow(new BusinessRuleException("year must be between 2000 and 2026 (got 1999)"));

        mvc.perform(get("/api/reports/quarterly").param("year", "1999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Business rule violated"))
                .andExpect(jsonPath("$.detail").value("year must be between 2000 and 2026 (got 1999)"));
    }

    @Test
    void nonNumericYearMapsToProblemJson() throws Exception {
        // A non-numeric year fails type conversion before the controller body runs.
        mvc.perform(get("/api/reports/quarterly").param("year", "notayear"))
                .andExpect(status().is4xxClientError());
    }

    // =====================================================================
    // Exports
    // =====================================================================

    @Test
    void quarterlyExcel_streamsWorkbookWithAttachmentFilename() throws Exception {
        when(reports.quarterly(2025)).thenReturn(sampleQuarterly(2025));
        when(exporter.toExcel(org.mockito.ArgumentMatchers.any())).thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/reports/quarterly/export.xlsx").param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("claims-quarterly-2025.xlsx")));

        verify(reports).quarterly(2025);
        verify(exporter).toExcel(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void quarterlyPdf_streamsPdfWithAttachmentFilename() throws Exception {
        when(reports.quarterly(2025)).thenReturn(sampleQuarterly(2025));
        when(exporter.toPdf(org.mockito.ArgumentMatchers.any())).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mvc.perform(get("/api/reports/quarterly/export.pdf").param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("claims-quarterly-2025.pdf")));
    }

    @Test
    void annualExcel_passesYearAndSpanAndNamesFile() throws Exception {
        ReportResponse resp = new ReportResponse(PeriodType.ANNUAL, 2024, List.of(),
                0, BigDecimal.ZERO, BigDecimal.ZERO);
        when(reports.annual(2024, 3)).thenReturn(resp);
        when(exporter.toExcel(org.mockito.ArgumentMatchers.any())).thenReturn(new byte[]{1});

        mvc.perform(get("/api/reports/annual/export.xlsx").param("year", "2024").param("years", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("claims-annual-2024.xlsx")));

        verify(reports).annual(2024, 3);
    }

    @Test
    void annualPdf_defaultsSpanToFive() throws Exception {
        int currentYear = Year.now(ZoneOffset.UTC).getValue();
        ReportResponse resp = new ReportResponse(PeriodType.ANNUAL, currentYear, List.of(),
                0, BigDecimal.ZERO, BigDecimal.ZERO);
        when(reports.annual(eq(currentYear), anyInt())).thenReturn(resp);
        when(exporter.toPdf(org.mockito.ArgumentMatchers.any())).thenReturn(new byte[]{'%', 'P', 'D', 'F'});

        mvc.perform(get("/api/reports/annual/export.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));

        verify(reports).annual(currentYear, 5);
    }
}
