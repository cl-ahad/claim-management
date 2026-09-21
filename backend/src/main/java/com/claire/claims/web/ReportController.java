package com.claire.claims.web;

import com.claire.claims.dto.ReportDtos.ReportResponse;
import com.claire.claims.service.ReportExporter;
import com.claire.claims.service.ReportingService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 *
 * The four export routes stream the same figures as the JSON endpoints as an
 * Excel workbook or a PDF (charts baked in). They reuse
 * {@link ReportingService} so an export can never disagree with the JSON /
 * on-screen report, and {@link ReportExporter} owns the byte generation.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ReportingService reports;
    private final ReportExporter exporter;

    public ReportController(ReportingService reports, ReportExporter exporter) {
        this.reports = reports;
        this.exporter = exporter;
    }

    /**
     * Quarterly report: the four quarters of {@code year} (defaults to the
     * current year), each with claims count, money totals and status breakdown.
     */
    @GetMapping("/quarterly")
    public ReportResponse quarterly(
            @RequestParam(required = false) Integer year) {
        return reports.quarterly(resolveYear(year));
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
        return reports.annual(resolveYear(year), years);
    }

    // =====================================================================
    // Exports - same params as the JSON endpoints, streamed as file bytes.
    // =====================================================================

    @GetMapping("/quarterly/export.xlsx")
    public ResponseEntity<byte[]> quarterlyExcel(
            @RequestParam(required = false) Integer year) {
        int y = resolveYear(year);
        return file(exporter.toExcel(reports.quarterly(y)), XLSX, "claims-quarterly-" + y + ".xlsx");
    }

    @GetMapping("/quarterly/export.pdf")
    public ResponseEntity<byte[]> quarterlyPdf(
            @RequestParam(required = false) Integer year) {
        int y = resolveYear(year);
        return file(exporter.toPdf(reports.quarterly(y)), MediaType.APPLICATION_PDF,
                "claims-quarterly-" + y + ".pdf");
    }

    @GetMapping("/annual/export.xlsx")
    public ResponseEntity<byte[]> annualExcel(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "5") int years) {
        int y = resolveYear(year);
        return file(exporter.toExcel(reports.annual(y, years)), XLSX, "claims-annual-" + y + ".xlsx");
    }

    @GetMapping("/annual/export.pdf")
    public ResponseEntity<byte[]> annualPdf(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false, defaultValue = "5") int years) {
        int y = resolveYear(year);
        return file(exporter.toPdf(reports.annual(y, years)), MediaType.APPLICATION_PDF,
                "claims-annual-" + y + ".pdf");
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String filename) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename)
                .build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(body.length)
                .body(body);
    }

    private static int resolveYear(Integer year) {
        return year != null ? year : Year.now(ZoneOffset.UTC).getValue();
    }
}
