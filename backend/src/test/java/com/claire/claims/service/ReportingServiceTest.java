package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.dto.ReportDtos.PeriodReport;
import com.claire.claims.dto.ReportDtos.PeriodType;
import com.claire.claims.dto.ReportDtos.ReportResponse;
import com.claire.claims.dto.ReportDtos.StatusCount;
import com.claire.claims.repository.ClaimRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the aggregation math without a database: the repository is mocked and
 * we assert the windows requested, the per-period rollups and the input guards.
 */
@ExtendWith(MockitoExtension.class)
class ReportingServiceTest {

    @Mock
    ClaimRepository claims;

    @InjectMocks
    ReportingService service;

    private static Object[] row(ClaimStatus status, long count, String charged, String paid) {
        return new Object[]{status, count, new BigDecimal(charged), new BigDecimal(paid)};
    }

    @Test
    void quarterly_returnsFourPeriodsWithHalfOpenCalendarWindows() {
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of());

        ReportResponse report = service.quarterly(2025);

        assertThat(report.type()).isEqualTo(PeriodType.QUARTERLY);
        assertThat(report.year()).isEqualTo(2025);
        assertThat(report.periods()).hasSize(4);
        assertThat(report.periods()).extracting(PeriodReport::label)
                .containsExactly("Q1 2025", "Q2 2025", "Q3 2025", "Q4 2025");

        // Q1 = [2025-01-01, 2025-04-01); Q4 = [2025-10-01, 2026-01-01).
        assertThat(report.periods().get(0).from()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(report.periods().get(0).to()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(report.periods().get(3).from()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(report.periods().get(3).to()).isEqualTo(LocalDate.of(2026, 1, 1));

        // One repository call per quarter, each with UTC start-of-day bounds.
        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(claims, times(4)).reportRowsByStatus(from.capture(), to.capture());
        assertThat(from.getAllValues().get(0))
                .isEqualTo(LocalDate.of(2025, 1, 1).atStartOfDay().atOffset(ZoneOffset.UTC));
        assertThat(to.getAllValues().get(0))
                .isEqualTo(LocalDate.of(2025, 4, 1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    @Test
    void everyStatusAppearsInTheBreakdownEvenWhenAbsentFromTheQuery() {
        // Only two statuses come back from the query.
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of(
                row(ClaimStatus.PAID, 3, "300.00", "300.00"),
                row(ClaimStatus.DENIED, 1, "100.00", "0.00")));

        ReportResponse report = service.quarterly(2025);
        PeriodReport q1 = report.periods().get(0);

        // The breakdown still lists every status in enum order.
        assertThat(q1.statusBreakdown()).extracting(StatusCount::status)
                .containsExactly(ClaimStatus.values());

        StatusCount paid = q1.statusBreakdown().stream()
                .filter(s -> s.status() == ClaimStatus.PAID).findFirst().orElseThrow();
        assertThat(paid.count()).isEqualTo(3);
        assertThat(paid.totalCharged()).isEqualByComparingTo("300.00");

        StatusCount draft = q1.statusBreakdown().stream()
                .filter(s -> s.status() == ClaimStatus.DRAFT).findFirst().orElseThrow();
        assertThat(draft.count()).isZero();
        assertThat(draft.totalCharged()).isEqualByComparingTo("0.00");
        assertThat(draft.description()).isEqualTo(ClaimStatus.DRAFT.getDescription());
    }

    @Test
    void periodAndReportTotalsSumTheStatusRows() {
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of(
                row(ClaimStatus.PAID, 2, "200.00", "200.00"),
                row(ClaimStatus.SUBMITTED, 1, "150.00", "0.00")));

        ReportResponse report = service.quarterly(2025);
        PeriodReport q1 = report.periods().get(0);

        assertThat(q1.claimsCount()).isEqualTo(3);
        assertThat(q1.totalCharged()).isEqualByComparingTo("350.00");
        assertThat(q1.totalPaid()).isEqualByComparingTo("200.00");

        // The same stub answers all four quarters, so report totals are 4x.
        assertThat(report.totalClaims()).isEqualTo(12);
        assertThat(report.totalCharged()).isEqualByComparingTo("1400.00");
        assertThat(report.totalPaid()).isEqualByComparingTo("800.00");
    }

    @Test
    void moneyIsScaledToTwoDecimalPlaces() {
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of(
                row(ClaimStatus.PAID, 1, "10.1", "5")));

        PeriodReport q1 = service.quarterly(2025).periods().get(0);
        assertThat(q1.totalCharged().scale()).isEqualTo(2);
        assertThat(q1.totalCharged()).isEqualByComparingTo("10.10");
        assertThat(q1.totalPaid()).isEqualByComparingTo("5.00");
    }

    @Test
    void annual_returnsRequestedSpanEndingAtTheYear() {
        when(claims.reportRowsByStatus(any(), any())).thenReturn(List.of());

        ReportResponse report = service.annual(2025, 3);

        assertThat(report.type()).isEqualTo(PeriodType.ANNUAL);
        assertThat(report.periods()).hasSize(3);
        assertThat(report.periods()).extracting(PeriodReport::label)
                .containsExactly("2023", "2024", "2025");
        assertThat(report.periods().get(0).from()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(report.periods().get(0).to()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(report.periods().get(2).to()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void rejectsYearBeforeTheFloor() {
        assertThatThrownBy(() -> service.quarterly(1999))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("year must be between");
    }

    @Test
    void rejectsYearInTheFuture() {
        int nextYear = Year.now(ZoneOffset.UTC).getValue() + 1;
        assertThatThrownBy(() -> service.annual(nextYear, 1))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("year must be between");
    }

    @Test
    void rejectsNonPositiveAnnualSpan() {
        assertThatThrownBy(() -> service.annual(2025, 0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("years must be between");
    }
}
