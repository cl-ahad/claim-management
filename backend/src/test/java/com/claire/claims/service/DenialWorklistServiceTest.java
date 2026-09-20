package com.claire.claims.service;

import com.claire.claims.domain.Claim;
import com.claire.claims.domain.ClaimAppeal;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.DenialGroupCode;
import com.claire.claims.domain.DenialReason;
import com.claire.claims.domain.DenialSource;
import com.claire.claims.domain.Patient;
import com.claire.claims.domain.Payer;
import com.claire.claims.domain.Provider;
import com.claire.claims.dto.DenialDtos.DeadlineSource;
import com.claire.claims.dto.DenialDtos.DenialWorklistItem;
import com.claire.claims.repository.ClaimAppealRepository;
import com.claire.claims.repository.ClaimRepository;
import com.claire.claims.repository.ClaimStatusHistoryRepository;
import com.claire.claims.repository.DenialReasonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The denial worklist, exercised without a database.
 *
 * The concern here is the read model the worklist builds: which claims appear,
 * the deadline each row races (stamped from an appeal vs projected for a fresh
 * denial), the display-only days-remaining / expired derivation, the ordering
 * by deadline, and that expired rows stay visible. RBAC and HTTP wiring are
 * proven in {@link com.claire.claims.web.DenialWorklistControllerTest}.
 *
 * A fixed reference date is passed into {@code buildWorklist} so every
 * derivation is deterministic - the public entry point supplies today.
 */
@ExtendWith(MockitoExtension.class)
class DenialWorklistServiceTest {

    @Mock private ClaimRepository claims;
    @Mock private DenialReasonRepository denialReasons;
    @Mock private ClaimAppealRepository appeals;
    @Mock private ClaimStatusHistoryRepository history;

    @InjectMocks private DenialWorklistService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    private Payer payer;

    @BeforeEach
    void setUp() {
        payer = payer(7L, "Acme Health", 90);
        // Default: no appeals, no reasons, no denial dates unless a test sets them.
        lenient().when(denialReasons.findByClaimIdInOrderByClaimIdAscCreatedAtAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        lenient().when(appeals.findByClaimIdInOrderByClaimIdAscLevelAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        lenient().when(history.latestTransitionInto(eq(ClaimStatus.DENIED), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
    }

    @Test
    void listsDeniedAndUnderAppealClaimsSortedByDeadlineAscending() {
        Claim a = claim(1L, "CLM-A", ClaimStatus.DENIED);
        Claim b = claim(2L, "CLM-B", ClaimStatus.APPEALED);
        when(claims.findByStatusIn(List.of(ClaimStatus.DENIED, ClaimStatus.APPEALED)))
                .thenReturn(List.of(a, b));

        // b carries a stamped appeal deadline sooner than a's projected one.
        when(appeals.findByClaimIdInOrderByClaimIdAscLevelAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(appeal(b, 1, LocalDate.of(2026, 3, 10))));
        when(history.latestTransitionInto(eq(ClaimStatus.DENIED), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, at(2026, 2, 20)})); // a denied 2/20 -> +90 = 5/21

        List<DenialWorklistItem> rows = service.buildWorklist(null, null, null, null, TODAY);

        assertThat(rows).extracting(DenialWorklistItem::claimNumber)
                .containsExactly("CLM-B", "CLM-A"); // 3/10 before 5/21
    }

    @Test
    void stampedDeadlineFromAppealIsUsedAndNotRecomputed() {
        Claim b = claim(2L, "CLM-B", ClaimStatus.APPEALED);
        when(claims.findByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(b));
        LocalDate stamped = LocalDate.of(2026, 4, 15);
        when(appeals.findByClaimIdInOrderByClaimIdAscLevelAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(appeal(b, 2, stamped)));

        DenialWorklistItem row = service.buildWorklist(null, null, null, null, TODAY).get(0);

        assertThat(row.deadline()).isEqualTo(stamped);
        assertThat(row.deadlineSource()).isEqualTo(DeadlineSource.STAMPED);
        assertThat(row.appealLevel()).isEqualTo(2);
        // Days remaining is a display value only, derived from today -> deadline.
        assertThat(row.daysRemaining()).isEqualTo(45L);
        assertThat(row.expired()).isFalse();
    }

    @Test
    void freshDenialGetsAProjectedDeadlineFromDenialDatePlusPayerWindow() {
        Claim a = claim(1L, "CLM-A", ClaimStatus.DENIED);
        when(claims.findByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(a));
        when(history.latestTransitionInto(eq(ClaimStatus.DENIED), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, at(2026, 2, 1)}));

        DenialWorklistItem row = service.buildWorklist(null, null, null, null, TODAY).get(0);

        assertThat(row.denialDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(row.deadline()).isEqualTo(LocalDate.of(2026, 2, 1).plusDays(90)); // 5/2
        assertThat(row.deadlineSource()).isEqualTo(DeadlineSource.PROSPECTIVE);
        assertThat(row.appealLevel()).isNull();
    }

    @Test
    void expiredClaimsRemainVisibleAndAreMarkedExpired() {
        Claim a = claim(1L, "CLM-A", ClaimStatus.DENIED);
        when(claims.findByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(a));
        // Denied long ago: projected deadline is in the past relative to TODAY.
        when(history.latestTransitionInto(eq(ClaimStatus.DENIED), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, at(2025, 1, 1)}));

        List<DenialWorklistItem> rows = service.buildWorklist(null, null, null, null, TODAY);

        assertThat(rows).hasSize(1); // not hidden
        DenialWorklistItem row = rows.get(0);
        assertThat(row.expired()).isTrue();
        assertThat(row.daysRemaining()).isNegative();
    }

    @Test
    void filtersByPayer() {
        Claim a = claim(1L, "CLM-A", ClaimStatus.DENIED);
        when(claims.findByStatusInAndPayerId(List.of(ClaimStatus.DENIED, ClaimStatus.APPEALED), 7L))
                .thenReturn(List.of(a));

        List<DenialWorklistItem> rows = service.buildWorklist(7L, null, null, null, TODAY);

        assertThat(rows).hasSize(1);
        verify(claims).findByStatusInAndPayerId(List.of(ClaimStatus.DENIED, ClaimStatus.APPEALED), 7L);
    }

    @Test
    void filtersByReasonCodeAcrossCarcAndRarc() {
        Claim a = claim(1L, "CLM-A", ClaimStatus.DENIED);
        Claim b = claim(2L, "CLM-B", ClaimStatus.DENIED);
        when(claims.findByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(a, b));
        when(denialReasons.findByClaimIdInOrderByClaimIdAscCreatedAtAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(
                        reason(a, "197", null),
                        reason(b, "45", "N130")));

        // Matches CARC on a.
        assertThat(service.buildWorklist(null, "197", null, null, TODAY))
                .extracting(DenialWorklistItem::claimNumber).containsExactly("CLM-A");
        // Matches RARC on b, case-insensitively.
        assertThat(service.buildWorklist(null, "n130", null, null, TODAY))
                .extracting(DenialWorklistItem::claimNumber).containsExactly("CLM-B");
        // No match.
        assertThat(service.buildWorklist(null, "999", null, null, TODAY)).isEmpty();
    }

    @Test
    void filtersByServiceDateRange() {
        Claim early = claim(1L, "CLM-EARLY", ClaimStatus.DENIED);
        early.setServiceDateFrom(LocalDate.of(2026, 1, 1));
        early.setServiceDateTo(LocalDate.of(2026, 1, 5));
        Claim late = claim(2L, "CLM-LATE", ClaimStatus.DENIED);
        late.setServiceDateFrom(LocalDate.of(2026, 2, 10));
        late.setServiceDateTo(LocalDate.of(2026, 2, 15));
        when(claims.findByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(early, late));

        List<DenialWorklistItem> rows =
                service.buildWorklist(null, null, LocalDate.of(2026, 2, 1), null, TODAY);

        assertThat(rows).extracting(DenialWorklistItem::claimNumber).containsExactly("CLM-LATE");
    }

    @Test
    void carriesTheStructuredReasonsOnEachRow() {
        Claim a = claim(1L, "CLM-A", ClaimStatus.DENIED);
        when(claims.findByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(a));
        when(denialReasons.findByClaimIdInOrderByClaimIdAscCreatedAtAsc(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(reason(a, "197", "N130"), reason(a, "45", null)));

        DenialWorklistItem row = service.buildWorklist(null, null, null, null, TODAY).get(0);

        assertThat(row.reasons()).hasSize(2);
        assertThat(row.reasons()).extracting("carcCode").containsExactly("197", "45");
        assertThat(row.reasons().get(0).claimId()).isEqualTo(1L);
    }

    @Test
    void emptyWhenNoDenialsExist() {
        when(claims.findByStatusIn(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());

        assertThat(service.buildWorklist(null, null, null, null, TODAY)).isEmpty();
    }

    // ----- fixtures -------------------------------------------------------

    private Claim claim(Long id, String number, ClaimStatus status) {
        Patient patient = new Patient();
        patient.setMrn("MRN" + id);
        patient.setFirstName("Pat");
        patient.setLastName("Ient" + id);
        Provider provider = new Provider();
        provider.setFirstName("Doc");
        provider.setLastName("Tor" + id);

        Claim c = new Claim();
        c.setId(id);
        c.setClaimNumber(number);
        c.setStatus(status);
        c.setPatient(patient);
        c.setProvider(provider);
        c.setPayer(payer);
        c.setServiceDateFrom(LocalDate.of(2026, 1, 1));
        c.setServiceDateTo(LocalDate.of(2026, 1, 10));
        c.setTotalCharge(new BigDecimal("500.00"));
        return c;
    }

    private static Payer payer(Long id, String name, int window) {
        Payer p = new Payer();
        p.setId(id);
        p.setName(name);
        p.setAppealWindowDays(window);
        return p;
    }

    private static ClaimAppeal appeal(Claim claim, int level, LocalDate deadline) {
        return new ClaimAppeal(claim, level, LocalDate.of(2026, 1, 15), deadline, "narrative", "biller");
    }

    private static DenialReason reason(Claim claim, String carc, String rarc) {
        return new DenialReason(claim, DenialGroupCode.CO, carc, rarc, DenialSource.MANUAL, null, "biller");
    }

    private static OffsetDateTime at(int y, int m, int d) {
        return OffsetDateTime.of(y, m, d, 12, 0, 0, 0, ZoneOffset.UTC);
    }
}
