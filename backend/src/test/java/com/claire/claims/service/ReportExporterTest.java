package com.claire.claims.service;

import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.dto.ReportDtos.PeriodReport;
import com.claire.claims.dto.ReportDtos.PeriodType;
import com.claire.claims.dto.ReportDtos.ReportResponse;
import com.claire.claims.dto.ReportDtos.StatusCount;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exporter turns a computed {@link ReportResponse} into file bytes. These
 * tests do not re-check the aggregation math (that is {@link ReportingServiceTest});
 * they pin that the output is a real, non-empty XLSX / PDF and that the workbook
 * carries the report's periods and totals.
 */
class ReportExporterTest {

    private final ReportExporter exporter = new ReportExporter();

    private static StatusCount status(ClaimStatus s, long count, String charged, String paid) {
        return new StatusCount(s, s.getDescription(), count,
                new BigDecimal(charged), new BigDecimal(paid));
    }

    private static PeriodReport period(String label, int year, int month, long count,
                                       String charged, String paid) {
        List<StatusCount> breakdown = new ArrayList<>();
        for (ClaimStatus s : ClaimStatus.values()) {
            breakdown.add(status(s, s == ClaimStatus.PAID ? count : 0,
                    s == ClaimStatus.PAID ? charged : "0.00",
                    s == ClaimStatus.PAID ? paid : "0.00"));
        }
        return new PeriodReport(label, LocalDate.of(year, month, 1),
                LocalDate.of(year, month, 1).plusMonths(3), count,
                new BigDecimal(charged), new BigDecimal(paid), breakdown);
    }

    private static ReportResponse quarterly2025() {
        List<PeriodReport> periods = List.of(
                period("Q1 2025", 2025, 1, 2, "200.00", "150.00"),
                period("Q2 2025", 2025, 4, 3, "300.00", "250.00"),
                period("Q3 2025", 2025, 7, 0, "0.00", "0.00"),
                period("Q4 2025", 2025, 10, 1, "100.00", "0.00"));
        return new ReportResponse(PeriodType.QUARTERLY, 2025, periods,
                6, new BigDecimal("600.00"), new BigDecimal("400.00"));
    }

    @Test
    void excelStartsWithZipSignatureAndOpens() throws Exception {
        byte[] bytes = exporter.toExcel(quarterly2025());

        assertThat(bytes).isNotEmpty();
        // XLSX is a zip container: "PK\03\04".
        assertThat(bytes[0]).isEqualTo((byte) 0x50);
        assertThat(bytes[1]).isEqualTo((byte) 0x4B);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            Sheet quarters = wb.getSheet("Quarters");
            assertThat(quarters).isNotNull();
            // Rows 0..2 are title, spacer and header, so the first period label
            // is at row 3.
            assertThat(quarters.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Q1 2025");
            assertThat(quarters.getRow(3).getCell(3).getNumericCellValue()).isEqualTo(2d);
        }
    }

    @Test
    void annualExcelSheetIsNamedYears() throws Exception {
        ReportResponse annual = new ReportResponse(PeriodType.ANNUAL, 2025,
                List.of(period("2025", 2025, 1, 4, "400.00", "300.00")),
                4, new BigDecimal("400.00"), new BigDecimal("300.00"));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(exporter.toExcel(annual)))) {
            assertThat(wb.getSheet("Years")).isNotNull();
        }
    }

    @Test
    void pdfStartsWithPdfSignature() {
        byte[] bytes = exporter.toPdf(quarterly2025());

        assertThat(bytes).isNotEmpty();
        // A PDF file begins with "%PDF".
        assertThat(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
    }

    @Test
    void handlesAnEmptyReportWithoutFailing() {
        ReportResponse empty = new ReportResponse(PeriodType.QUARTERLY, 2025,
                List.of(period("Q1 2025", 2025, 1, 0, "0.00", "0.00")),
                0, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(exporter.toExcel(empty)).isNotEmpty();
        assertThat(exporter.toPdf(empty)).isNotEmpty();
    }
}
