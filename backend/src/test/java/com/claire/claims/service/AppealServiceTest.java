package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.common.ApiExceptions.ConflictException;
import com.claire.claims.common.ApiExceptions.NotFoundException;
import com.claire.claims.domain.AppealOutcome;
import com.claire.claims.domain.AppealStatus;
import com.claire.claims.domain.Claim;
import com.claire.claims.domain.ClaimAppeal;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.Payer;
import com.claire.claims.dto.AppealDtos.AppealResponse;
import com.claire.claims.dto.AppealDtos.FileAppealRequest;
import com.claire.claims.dto.AppealDtos.RecordOutcomeRequest;
import com.claire.claims.dto.ClaimDtos.TransitionRequest;
import com.claire.claims.repository.ClaimAppealRepository;
import com.claire.claims.repository.ClaimRepository;
import com.claire.claims.repository.DenialReasonRepository;
import com.claire.claims.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Business rules for the appeals lifecycle, exercised without a database. The
 * state machine (which transitions and audit rows it writes) is proven
 * directly in {@link ClaimStatusMachineAppealTest}; RBAC and HTTP status
 * mapping in {@link com.claire.claims.web.AppealControllerTest}. Here the
 * concern is the filing/escalation/outcome rules and that every status change
 * is driven THROUGH ClaimService.transition, never set on the claim directly.
 */
@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

    @Mock private ClaimRepository claims;
    @Mock private ClaimAppealRepository appeals;
    @Mock private DenialReasonRepository denialReasons;
    @Mock private ClaimService claimService;
    @Mock private CurrentUserProvider currentUser;

    @InjectMocks private AppealService service;

    private Claim claim;

    @BeforeEach
    void setUp() {
        Payer payer = new Payer();
        payer.setAppealWindowDays(90);

        claim = new Claim();
        claim.setId(4L);
        claim.setClaimNumber("CLM-2026-000004");
        claim.setPayer(payer);
        claim.setStatus(ClaimStatus.DENIED);
        claim.setTotalCharge(new BigDecimal("500.00"));

        lenient().when(currentUser.username()).thenReturn("biller");
        lenient().when(appeals.save(any(ClaimAppeal.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ----- filing ---------------------------------------------------------

    @Test
    void filesAnAppealAndDrivesTheTransitionThroughTheStateMachine() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(denialReasons.existsByClaimId(4L)).thenReturn(true);
        when(appeals.findByClaimIdOrderByLevelAscFiledOnAsc(4L)).thenReturn(List.of());

        AppealResponse res = service.file(4L, new FileAppealRequest(null, "please reconsider"));

        assertThat(res.level()).isEqualTo(1);
        assertThat(res.status()).isEqualTo(AppealStatus.OPEN);
        // Deadline stamped from the payer window (90 days), not left null.
        assertThat(res.deadline()).isEqualTo(LocalDate.now().plusDays(90));
        assertThat(res.filedBy()).isEqualTo("biller");

        // The status change went THROUGH the state machine, not set directly.
        ArgumentCaptor<TransitionRequest> tx = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(claimService).transition(eq(4L), tx.capture());
        assertThat(tx.getValue().targetStatus()).isEqualTo(ClaimStatus.APPEALED);
    }

    @Test
    void appealOnANotDeniedClaimIsConflict() {
        claim.setStatus(ClaimStatus.SUBMITTED);
        when(claims.findById(4L)).thenReturn(Optional.of(claim));

        assertThatThrownBy(() -> service.file(4L, new FileAppealRequest(null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("only a DENIED claim");

        verify(appeals, never()).save(any());
        verify(claimService, never()).transition(any(), any());
    }

    @Test
    void appealWithNoStructuredDenialReasonIsBadRequest() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(denialReasons.existsByClaimId(4L)).thenReturn(false);

        assertThatThrownBy(() -> service.file(4L, new FileAppealRequest(null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no structured denial reason");

        verify(appeals, never()).save(any());
    }

    @Test
    void secondOpenAppealIsConflict() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(denialReasons.existsByClaimId(4L)).thenReturn(true);
        when(appeals.existsByClaimIdAndStatus(4L, AppealStatus.OPEN)).thenReturn(true);

        assertThatThrownBy(() -> service.file(4L, new FileAppealRequest(null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has an open appeal");

        verify(appeals, never()).save(any());
    }

    @Test
    void escalatingToLevel2AfterUpheldStampsNothingNewAndCarriesTheDeadline() {
        LocalDate stamped = LocalDate.now().plusDays(30);
        ClaimAppeal level1 = new ClaimAppeal(claim, 1, LocalDate.now().minusDays(5),
                stamped, "l1", "biller");
        level1.setOutcome(AppealOutcome.UPHELD);
        level1.setStatus(AppealStatus.CLOSED);

        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(denialReasons.existsByClaimId(4L)).thenReturn(true);
        when(appeals.findByClaimIdOrderByLevelAscFiledOnAsc(4L)).thenReturn(List.of(level1));

        AppealResponse res = service.file(4L, new FileAppealRequest(2, "formal review"));

        assertThat(res.level()).isEqualTo(2);
        // The deadline is carried forward from level 1, never recomputed.
        assertThat(res.deadline()).isEqualTo(stamped);
    }

    @Test
    void escalatingToLevel2WithoutAnUpheldLevel1IsRejected() {
        ClaimAppeal level1 = new ClaimAppeal(claim, 1, LocalDate.now(),
                LocalDate.now().plusDays(90), "l1", "biller");
        level1.setOutcome(AppealOutcome.OVERTURNED);
        level1.setStatus(AppealStatus.CLOSED);

        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(denialReasons.existsByClaimId(4L)).thenReturn(true);
        when(appeals.findByClaimIdOrderByLevelAscFiledOnAsc(4L)).thenReturn(List.of(level1));

        assertThatThrownBy(() -> service.file(4L, new FileAppealRequest(2, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("only allowed after");
    }

    @Test
    void appealAfterTheDeadlineIsBadRequestNamingTheDeadline() {
        // Level 1 was UPHELD but its stamped deadline is already in the past;
        // escalating to level 2 today is refused, and the message names it.
        LocalDate past = LocalDate.now().minusDays(1);
        ClaimAppeal level1 = new ClaimAppeal(claim, 1, LocalDate.now().minusDays(120),
                past, "l1", "biller");
        level1.setOutcome(AppealOutcome.UPHELD);
        level1.setStatus(AppealStatus.CLOSED);

        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(denialReasons.existsByClaimId(4L)).thenReturn(true);
        when(appeals.findByClaimIdOrderByLevelAscFiledOnAsc(4L)).thenReturn(List.of(level1));

        assertThatThrownBy(() -> service.file(4L, new FileAppealRequest(2, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(past.toString())
                .hasMessageContaining("has passed");

        verify(appeals, never()).save(any());
    }

    @Test
    void invalidEscalationLevelIsRejected() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(denialReasons.existsByClaimId(4L)).thenReturn(true);

        assertThatThrownBy(() -> service.file(4L, new FileAppealRequest(4, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("level must be 1");
    }

    @Test
    void unknownClaimIsNotFound() {
        when(claims.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.file(999L, new FileAppealRequest(null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    // ----- outcomes -------------------------------------------------------

    @Test
    void overturnedClosesTheAppealAndDrivesPaid() {
        ClaimAppeal open = openAppeal(1);
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(appeals.findByClaimIdAndStatus(4L, AppealStatus.OPEN)).thenReturn(Optional.of(open));

        AppealResponse res = service.recordOutcome(4L,
                new RecordOutcomeRequest(AppealOutcome.OVERTURNED, null, null, "won on review"));

        assertThat(res.status()).isEqualTo(AppealStatus.CLOSED);
        assertThat(res.outcome()).isEqualTo(AppealOutcome.OVERTURNED);
        assertThat(res.decidedOn()).isEqualTo(LocalDate.now());

        ArgumentCaptor<TransitionRequest> tx = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(claimService).transition(eq(4L), tx.capture());
        assertThat(tx.getValue().targetStatus()).isEqualTo(ClaimStatus.PAID);
        // OVERTURNED settles in full: paid defaults to the claim charge.
        assertThat(tx.getValue().paidAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void partialClosesTheAppealAndDrivesPartiallyPaidWithTheAmount() {
        ClaimAppeal open = openAppeal(1);
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(appeals.findByClaimIdAndStatus(4L, AppealStatus.OPEN)).thenReturn(Optional.of(open));

        AppealResponse res = service.recordOutcome(4L, new RecordOutcomeRequest(
                AppealOutcome.PARTIAL, new BigDecimal("500.00"), new BigDecimal("200.00"), null));

        assertThat(res.status()).isEqualTo(AppealStatus.CLOSED);

        ArgumentCaptor<TransitionRequest> tx = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(claimService).transition(eq(4L), tx.capture());
        assertThat(tx.getValue().targetStatus()).isEqualTo(ClaimStatus.PARTIALLY_PAID);
        assertThat(tx.getValue().paidAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void upheldClosesTheAppealAndDrivesDenied() {
        ClaimAppeal open = openAppeal(1);
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(appeals.findByClaimIdAndStatus(4L, AppealStatus.OPEN)).thenReturn(Optional.of(open));

        AppealResponse res = service.recordOutcome(4L,
                new RecordOutcomeRequest(AppealOutcome.UPHELD, null, null, null));

        assertThat(res.status()).isEqualTo(AppealStatus.CLOSED);
        ArgumentCaptor<TransitionRequest> tx = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(claimService).transition(eq(4L), tx.capture());
        assertThat(tx.getValue().targetStatus()).isEqualTo(ClaimStatus.DENIED);
    }

    @Test
    void withdrawnMarksTheAppealWithdrawnNotDeletedAndDrivesDenied() {
        ClaimAppeal open = openAppeal(1);
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(appeals.findByClaimIdAndStatus(4L, AppealStatus.OPEN)).thenReturn(Optional.of(open));

        AppealResponse res = service.recordOutcome(4L,
                new RecordOutcomeRequest(AppealOutcome.WITHDRAWN, null, null, "filed by mistake"));

        // A mistaken filing is WITHDRAWN, and the row is kept, not deleted.
        assertThat(res.status()).isEqualTo(AppealStatus.WITHDRAWN);
        verify(appeals, never()).delete(any());
        verify(appeals, never()).deleteById(any());

        ArgumentCaptor<TransitionRequest> tx = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(claimService).transition(eq(4L), tx.capture());
        assertThat(tx.getValue().targetStatus()).isEqualTo(ClaimStatus.DENIED);
    }

    @Test
    void recordingAnOutcomeWithNoOpenAppealIsConflict() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        when(appeals.findByClaimIdAndStatus(4L, AppealStatus.OPEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordOutcome(4L,
                new RecordOutcomeRequest(AppealOutcome.OVERTURNED, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no open appeal");

        verify(claimService, never()).transition(any(), any());
    }

    // ----- reads ----------------------------------------------------------

    @Test
    void listAppealsIsScopedToTheClaim() {
        when(claims.existsById(4L)).thenReturn(true);
        when(appeals.findByClaimIdOrderByLevelAscFiledOnAsc(4L))
                .thenReturn(List.of(openAppeal(1)));

        List<AppealResponse> res = service.appealsFor(4L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).claimId()).isEqualTo(4L);
        verify(appeals).findByClaimIdOrderByLevelAscFiledOnAsc(4L);
    }

    @Test
    void listAppealsForUnknownClaimIsNotFound() {
        when(claims.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.appealsFor(999L))
                .isInstanceOf(NotFoundException.class);
    }

    private ClaimAppeal openAppeal(int level) {
        return new ClaimAppeal(claim, level, LocalDate.now(),
                LocalDate.now().plusDays(90), "narrative", "biller");
    }
}
