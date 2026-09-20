package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.dto.ReportDtos.*;
import com.claire.claims.repository.ClaimRepository;
import com.claire.claims.repository.ClaimStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the reporting aggregation math. Repositories are mocked, so
 * these pin the metric definitions (counts, status mapping, totals, average
 * processing time) and the period-window resolution without a database.
 */
@ExtendWith(MockitoExtension.class)
class ReportingServiceTest {

    @Mock ClaimRepository claims;
    @Mock ClaimStatusHistoryRepository history;
    @InjectMocks ReportingService service;

    private static Object[] row(ClaimStatus status, long count, String charge, String paid) {
        return new Object[]{status, count, new BigDecimal(charge), new BigDecimal(paid)};
    }

    // ----- period resolution ---------------------------------------------

    @Test
    void resolvesQuarterToHalfOpenWindow() {
        ReportPeriod p = service.resolvePeriod(PeriodType.QUARTER, 2026, 2);

        assertThat(p.type()).isEqualTo(PeriodType.QUARTER);
        assertThat(p.year()).isEqualTo(2026);
        assertThat(p.quarter()).isEqualTo(2);
        assertThat(p.label()).isEqualTo("Q2 2026");
        assertThat(p.from()).isEqualTo(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(p.to()).isEqualTo(OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void resolvesYearToHalfOpenWindow() {
        ReportPeriod p = service.resolvePeriod(PeriodType.YEAR, 2026, null);

        assertThat(p.quarter()).isNull();
        assertThat(p.label()).isEqualTo("FY 2026");
        assertThat(p.from()).isEqualTo(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        assertThat(p.to()).isEqualTo(OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void quarterRequiresAQuarterNumber() {
        assertThatThrownBy(() -> service.resolvePeriod(PeriodType.QUARTER, 2026, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quarter");
    }

    @Test
    void rejectsQuarterOutOfRange() {
        assertThatThrownBy(() -> service.resolvePeriod(PeriodType.QUARTER, 2026, 5))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("between 1 and 4");
    }

    @Test
    void rejectsYearOutOfRange() {
        assertThatThrownBy(() -> service.resolvePeriod(PeriodType.YEAR, 1999, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("year must be between");
    }

    // ----- status mapping and totals -------------------------------------

    @Test
    void mapsStatusesIntoApprovedRejectedPendingVoidAndTotals() {
        // approved: PAID, PARTIALLY_PAID, ACCEPTED | rejected: REJECTED, DENIED
        // pending: DRAFT, SUBMITTED, APPEALED | void: VOID (separate)
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of(
                row(ClaimStatus.PAID, 3, "300.00", "300.00"),
                row(ClaimStatus.PARTIALLY_PAID, 1, "100.00", "40.00"),
                row(ClaimStatus.ACCEPTED, 1, "100.00", "0.00"),
                row(ClaimStatus.REJECTED, 2, "200.00", "0.00"),
                row(ClaimStatus.DENIED, 1, "100.00", "0.00"),
                row(ClaimStatus.DRAFT, 1, "100.00", "0.00"),
                row(ClaimStatus.SUBMITTED, 1, "100.00", "0.00"),
                row(ClaimStatus.VOID, 1, "100.00", "0.00")));
        when(history.firstTerminalDecisionAt(any(), any(), any())).thenReturn(List.of());

        ReportResponse r = service.report(PeriodType.YEAR, 2026, null);

        assertThat(r.claimsFiled()).isEqualTo(11);
        assertThat(r.approved()).isEqualTo(5);   // 3 + 1 + 1
        assertThat(r.rejected()).isEqualTo(3);    // 2 + 1
        assertThat(r.pending()).isEqualTo(2);     // 1 + 1
        assertThat(r.voided()).isEqualTo(1);
        // groups reconcile to claims filed
        assertThat(r.approved() + r.rejected() + r.pending() + r.voided())
                .isEqualTo(r.claimsFiled());

        assertThat(r.totalCharged()).isEqualByComparingTo("1100.00");
        assertThat(r.totalPaid()).isEqualByComparingTo("340.00");

        // shares are percentages to one decimal
        StatusGroupCount approvedGroup = r.statusGroups().stream()
                .filter(g -> g.group().equals("approved")).findFirst().orElseThrow();
        assertThat(approvedGroup.share()).isEqualByComparingTo("45.5"); // 5/11
    }

    @Test
    void emptyWindowYieldsZeroesNotNulls() {
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of());
        when(history.firstTerminalDecisionAt(any(), any(), any())).thenReturn(List.of());

        ReportResponse r = service.report(PeriodType.QUARTER, 2026, 1);

        assertThat(r.claimsFiled()).isZero();
        assertThat(r.totalCharged()).isEqualByComparingTo("0.00");
        assertThat(r.processingTime().averageDays()).isEqualByComparingTo("0.0");
        assertThat(r.processingTime().decidedClaims()).isZero();
        assertThat(r.processingTime().openClaims()).isZero();
        assertThat(r.statusGroups()).extracting(StatusGroupCount::share)
                .allMatch(s -> s.compareTo(BigDecimal.ZERO) == 0);
    }

    // ----- average processing time ---------------------------------------

    @Test
    void averagesProcessingTimeOverDecidedClaimsAndReportsOpenDenominator() {
        // 4 claims filed; 2 decided (2 days and 4 days), 2 still open.
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of(
                row(ClaimStatus.PAID, 1, "100.00", "100.00"),
                row(ClaimStatus.DENIED, 1, "100.00", "0.00"),
                row(ClaimStatus.SUBMITTED, 2, "200.00", "0.00")));

        OffsetDateTime created = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        when(history.firstTerminalDecisionAt(
                eq(List.of(ClaimStatus.PAID, ClaimStatus.PARTIALLY_PAID,
                           ClaimStatus.DENIED, ClaimStatus.REJECTED, ClaimStatus.VOID)),
                any(), any())).thenReturn(List.of(
                new Object[]{1L, created, created.plusDays(2)},
                new Object[]{2L, created, created.plusDays(4)}));

        ReportResponse r = service.report(PeriodType.YEAR, 2026, null);

        assertThat(r.claimsFiled()).isEqualTo(4);
        assertThat(r.processingTime().decidedClaims()).isEqualTo(2);
        assertThat(r.processingTime().openClaims()).isEqualTo(2);
        assertThat(r.processingTime().averageDays()).isEqualByComparingTo("3.0"); // (2+4)/2
    }

    @Test
    void clampsNegativeDurationFromClockSkewToZero() {
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of(
                row(ClaimStatus.PAID, 1, "100.00", "100.00")));

        OffsetDateTime created = OffsetDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC);
        when(history.firstTerminalDecisionAt(any(), any(), any())).thenReturn(List.of(
                new Object[]{1L, created, created.minusDays(1)})); // decided "before" created

        ReportResponse r = service.report(PeriodType.YEAR, 2026, null);

        assertThat(r.processingTime().decidedClaims()).isEqualTo(1);
        assertThat(r.processingTime().averageDays()).isEqualByComparingTo("0.0");
    }
}
