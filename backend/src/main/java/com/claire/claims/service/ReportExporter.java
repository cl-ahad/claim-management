package com.claire.claims.service;

import com.claire.claims.dto.ReportDtos.PeriodReport;
import com.claire.claims.dto.ReportDtos.PeriodType;
import com.claire.claims.dto.ReportDtos.ReportResponse;
import com.claire.claims.dto.ReportDtos.StatusCount;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;

/**
 * Turns a computed {@link ReportResponse} into downloadable Excel and PDF bytes.
 *
 * Kept separate from {@link ReportingService} so aggregation carries no POI /
 * OpenPDF / JFreeChart dependency and stays unit-testable without them. This
 * class does no aggregation of its own: it renders exactly the figures the JSON
 * endpoints and the charts already show, so an export can never disagree with
 * the on-screen report.
 *
 * Charts are baked server-side as PNGs with JFreeChart and embedded in the PDF,
 * so the "graphs in the export" requirement is met without the browser.
 */
@Service
public class ReportExporter {

    // Chart canvas size (px). Kept modest so the PDF stays small.
    private static final int CHART_WIDTH = 720;
    private static final int CHART_HEIGHT = 300;

    private static final Color ACCENT = new Color(0x2F, 0x6F, 0xED); // charged / count
    private static final Color OK = new Color(0x1F, 0x9D, 0x55);      // collected

    // =====================================================================
    // Excel
    // =====================================================================

    /**
     * Excel workbook with a Summary sheet, a per-period sheet (counts and money)
     * and a Status breakdown sheet. Uses the streaming writer so a large report
     * does not hold every row in memory at once.
     */
    public byte[] toExcel(ReportResponse report) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle title = titleStyle(wb);
            CellStyle header = headerStyle(wb);
            CellStyle money = moneyStyle(wb);

            writeSummarySheet(wb, report, title, header, money);
            writePeriodsSheet(wb, report, title, header, money);
            writeStatusSheet(wb, report, title, header, money);

            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build the Excel report", e);
        }
    }

    private void writeSummarySheet(Workbook wb, ReportResponse report,
                                   CellStyle title, CellStyle header, CellStyle money) {
        Sheet sheet = wb.createSheet("Summary");
        int r = 0;

        Row titleRow = sheet.createRow(r++);
        cell(titleRow, 0, reportTitle(report), title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
        r++; // blank spacer row

        Row head = sheet.createRow(r++);
        cell(head, 0, "Metric", header);
        cell(head, 1, "Value", header);

        row2(sheet, r++, "Report type", report.type().name());
        row2(sheet, r++, "Anchor year", String.valueOf(report.year()));
        row2(sheet, r++, "Periods", String.valueOf(report.periods().size()));
        row2(sheet, r++, "Total claims", String.valueOf(report.totalClaims()));
        moneyRow(sheet, r++, "Total charged", report.totalCharged(), money);
        moneyRow(sheet, r++, "Total collected", report.totalPaid(), money);

        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 20 * 256);
    }

    private void writePeriodsSheet(Workbook wb, ReportResponse report,
                                   CellStyle title, CellStyle header, CellStyle money) {
        Sheet sheet = wb.createSheet(report.type() == PeriodType.QUARTERLY ? "Quarters" : "Years");
        int r = 0;

        Row titleRow = sheet.createRow(r++);
        cell(titleRow, 0, "Claims by period", title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
        r++;

        Row head = sheet.createRow(r++);
        cell(head, 0, "Period", header);
        cell(head, 1, "From", header);
        cell(head, 2, "To", header);
        cell(head, 3, "Claims", header);
        cell(head, 4, "Charged", header);
        cell(head, 5, "Collected", header);

        for (PeriodReport p : report.periods()) {
            Row row = sheet.createRow(r++);
            cell(row, 0, p.label(), null);
            cell(row, 1, p.from().toString(), null);
            cell(row, 2, p.to().toString(), null);
            numberCell(row, 3, p.claimsCount());
            moneyCell(row, 4, p.totalCharged(), money);
            moneyCell(row, 5, p.totalPaid(), money);
        }

        for (int c = 0; c <= 5; c++) {
            sheet.setColumnWidth(c, 16 * 256);
        }
    }

    private void writeStatusSheet(Workbook wb, ReportResponse report,
                                  CellStyle title, CellStyle header, CellStyle money) {
        Sheet sheet = wb.createSheet("Status breakdown");
        int r = 0;

        Row titleRow = sheet.createRow(r++);
        cell(titleRow, 0, "Status breakdown by period", title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
        r++;

        Row head = sheet.createRow(r++);
        cell(head, 0, "Period", header);
        cell(head, 1, "Status", header);
        cell(head, 2, "Description", header);
        cell(head, 3, "Claims", header);
        cell(head, 4, "Charged", header);
        cell(head, 5, "Collected", header);

        for (PeriodReport p : report.periods()) {
            for (StatusCount s : p.statusBreakdown()) {
                Row row = sheet.createRow(r++);
                cell(row, 0, p.label(), null);
                cell(row, 1, s.status().name(), null);
                cell(row, 2, s.description(), null);
                numberCell(row, 3, s.count());
                moneyCell(row, 4, s.totalCharged(), money);
                moneyCell(row, 5, s.totalPaid(), money);
            }
        }

        sheet.setColumnWidth(0, 14 * 256);
        sheet.setColumnWidth(1, 16 * 256);
        sheet.setColumnWidth(2, 40 * 256);
        for (int c = 3; c <= 5; c++) {
            sheet.setColumnWidth(c, 16 * 256);
        }
    }

    // =====================================================================
    // PDF
    // =====================================================================

    /**
     * PDF report: a heading, the summary, the two charts baked as PNGs
     * (claims count over time, charged vs collected over time) and the
     * per-period table.
     */
    public byte[] toPdf(ReportResponse report) {
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font h1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, new Color(0x11, 0x18, 0x27));
            Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(0x37, 0x41, 0x51));
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(0x37, 0x41, 0x51));

            Paragraph heading = new Paragraph(reportTitle(report), h1);
            heading.setSpacingAfter(4);
            doc.add(heading);

            doc.add(new Paragraph(
                    "Total claims " + report.totalClaims()
                            + "   \u00b7   Charged " + report.totalCharged().toPlainString()
                            + "   \u00b7   Collected " + report.totalPaid().toPlainString(),
                    body));

            doc.add(spacer(10));
            doc.add(pngImage(countChart(report)));
            doc.add(spacer(8));
            doc.add(pngImage(valueChart(report)));
            doc.add(spacer(12));

            Paragraph tableTitle = new Paragraph("Claims by period", h2);
            tableTitle.setSpacingAfter(6);
            doc.add(tableTitle);
            doc.add(periodTable(report, h2, body));

            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            if (doc.isOpen()) {
                doc.close();
            }
            throw new UncheckedIOException("Failed to build the PDF report",
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    private PdfPTable periodTable(ReportResponse report, Font head, Font body) {
        PdfPTable table = new PdfPTable(new float[]{2.2f, 1.2f, 1.6f, 1.6f});
        table.setWidthPercentage(100);
        headCell(table, "Period", head);
        headCell(table, "Claims", head);
        headCell(table, "Charged", head);
        headCell(table, "Collected", head);
        for (PeriodReport p : report.periods()) {
            bodyCell(table, p.label(), body, Element.ALIGN_LEFT);
            bodyCell(table, String.valueOf(p.claimsCount()), body, Element.ALIGN_RIGHT);
            bodyCell(table, p.totalCharged().toPlainString(), body, Element.ALIGN_RIGHT);
            bodyCell(table, p.totalPaid().toPlainString(), body, Element.ALIGN_RIGHT);
        }
        return table;
    }

    private static void headCell(PdfPTable table, String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(new Color(0xF3, 0xF4, 0xF6));
        c.setPadding(5);
        c.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(c);
    }

    private static void bodyCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setPadding(5);
        c.setHorizontalAlignment(align);
        table.addCell(c);
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(height);
        return p;
    }

    private Image pngImage(byte[] png) throws DocumentException, IOException {
        Image img = Image.getInstance(png);
        img.setAlignment(Element.ALIGN_CENTER);
        img.scaleToFit(PageSize.A4.rotate().getWidth() - 72, CHART_HEIGHT);
        return img;
    }

    // =====================================================================
    // Charts (JFreeChart -> PNG)
    // =====================================================================

    private byte[] countChart(ReportResponse report) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (PeriodReport p : report.periods()) {
            ds.addValue(p.claimsCount(), "Claims", p.label());
        }
        JFreeChart chart = ChartFactory.createBarChart(
                "Claims count over time", "Period", "Claims",
                ds, PlotOrientation.VERTICAL, false, false, false);
        stylePlot(chart, ACCENT);
        return png(chart);
    }

    private byte[] valueChart(ReportResponse report) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (PeriodReport p : report.periods()) {
            ds.addValue(p.totalCharged(), "Charged", p.label());
            ds.addValue(p.totalPaid(), "Collected", p.label());
        }
        JFreeChart chart = ChartFactory.createBarChart(
                "Claim value over time", "Period", "Amount",
                ds, PlotOrientation.VERTICAL, true, false, false);
        CategoryPlot plot = stylePlot(chart, ACCENT);
        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setSeriesPaint(0, ACCENT);
        renderer.setSeriesPaint(1, OK);
        return png(chart);
    }

    private CategoryPlot stylePlot(JFreeChart chart, Color seriesColor) {
        chart.setBackgroundPaint(Color.WHITE);
        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setRangeGridlinePaint(new Color(0xE5, 0xE7, 0xEB));
        plot.setOutlineVisible(false);
        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setBarPainter(new StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setSeriesPaint(0, seriesColor);
        return plot;
    }

    private byte[] png(JFreeChart chart) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ChartUtils.writeChartAsPNG(out, chart, CHART_WIDTH, CHART_HEIGHT);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render a chart image", e);
        }
    }

    // =====================================================================
    // Excel cell helpers
    // =====================================================================

    private static String reportTitle(ReportResponse report) {
        String kind = report.type() == PeriodType.QUARTERLY ? "Quarterly" : "Annual";
        return kind + " claims report \u2014 " + report.year();
    }

    private static CellStyle titleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        return s;
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        org.apache.poi.ss.usermodel.Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private static CellStyle moneyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) {
            c.setCellStyle(style);
        }
    }

    private static void numberCell(Row row, int col, long value) {
        row.createCell(col).setCellValue(value);
    }

    private static void moneyCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value == null ? 0d : value.doubleValue());
        c.setCellStyle(style);
    }

    private static void row2(Sheet sheet, int r, String label, String value) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, null);
        cell(row, 1, value, null);
    }

    private static void moneyRow(Sheet sheet, int r, String label, BigDecimal value, CellStyle money) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, null);
        moneyCell(row, 1, value, money);
    }
}
