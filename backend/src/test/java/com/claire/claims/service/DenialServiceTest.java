package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.common.ApiExceptions.NotFoundException;
import com.claire.claims.domain.Claim;
import com.claire.claims.domain.DenialGroupCode;
import com.claire.claims.domain.DenialReason;
import com.claire.claims.domain.DenialSource;
import com.claire.claims.dto.DenialDtos.DenialReasonRequest;
import com.claire.claims.dto.DenialDtos.DenialReasonResponse;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Business rules for recording structured denial reasons, exercised without a
 * database: the state machine, RBAC and HTTP mapping are proven elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class DenialServiceTest {

    @Mock private ClaimRepository claims;
    @Mock private DenialReasonRepository denialReasons;
    @Mock private CurrentUserProvider currentUser;

    @InjectMocks private DenialService service;

    private Claim claim;

    @BeforeEach
    void setUp() {
        claim = new Claim();
        claim.setId(4L);
        lenient().when(currentUser.username()).thenReturn("biller");
        // save() returns its argument so the mapper can read it back.
        lenient().when(denialReasons.save(any(DenialReason.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordsAStructuredReason() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));

        DenialReasonResponse res = service.recordReason(4L,
                new DenialReasonRequest(DenialGroupCode.CO, "197", "N130", null, "No prior auth"));

        assertThat(res.groupCode()).isEqualTo(DenialGroupCode.CO);
        assertThat(res.carcCode()).isEqualTo("197");
        assertThat(res.rarcCode()).isEqualTo("N130");
        // Defaults to MANUAL when the caller does not say - this endpoint keys by hand.
        assertThat(res.source()).isEqualTo(DenialSource.MANUAL);
        assertThat(res.createdBy()).isEqualTo("biller");
    }

    @Test
    void rejectsAReasonWithNoStructuredCode() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));

        // Free text but no CARC code: not a structured reason.
        assertThatThrownBy(() -> service.recordReason(4L,
                new DenialReasonRequest(DenialGroupCode.CO, "  ", null, null, "denied, see letter")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("CARC");

        verify(denialReasons, never()).save(any());
    }

    @Test
    void unknownClaimIsNotFound() {
        when(claims.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordReason(999L,
                new DenialReasonRequest(DenialGroupCode.PR, "1", null, null, null)))
                .isInstanceOf(NotFoundException.class);

        verify(denialReasons, never()).save(any());
    }

    @Test
    void aDenialMayHaveMultipleReasons() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));

        service.recordReason(4L, new DenialReasonRequest(DenialGroupCode.CO, "197", null, null, null));
        service.recordReason(4L, new DenialReasonRequest(DenialGroupCode.PR, "1", null, null, null));

        verify(denialReasons, org.mockito.Mockito.times(2)).save(any(DenialReason.class));
    }

    @Test
    void remittanceSourceIsPreservedWhenSupplied() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));

        DenialReasonResponse res = service.recordReason(4L,
                new DenialReasonRequest(DenialGroupCode.OA, "45", null, DenialSource.REMITTANCE, null));

        assertThat(res.source()).isEqualTo(DenialSource.REMITTANCE);
    }

    @Test
    void bothManualAndRemittanceReasonsAreKeptForAudit() {
        // Rows are additive and never mutated: a manually keyed reason and an
        // automated (REMITTANCE) one both persist against the same claim, so the
        // manual entry survives for the audit trail rather than being overwritten.
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        ArgumentCaptor<DenialReason> saved = ArgumentCaptor.forClass(DenialReason.class);

        service.recordReason(4L,
                new DenialReasonRequest(DenialGroupCode.CO, "197", null, DenialSource.MANUAL, "keyed by hand"));
        service.recordReason(4L,
                new DenialReasonRequest(DenialGroupCode.CO, "197", null, DenialSource.REMITTANCE, "from 835"));

        verify(denialReasons, org.mockito.Mockito.times(2)).save(saved.capture());
        List<DenialReason> rows = saved.getAllValues();
        assertThat(rows).extracting(DenialReason::getSource)
                .containsExactly(DenialSource.MANUAL, DenialSource.REMITTANCE);
        // Neither row is deleted or its source rewritten - both are on the same claim.
        assertThat(rows).extracting(r -> r.getClaim().getId()).containsOnly(4L);
        verify(denialReasons, never()).delete(any());
        verify(denialReasons, never()).deleteById(any());
    }

    @Test
    void recordingIsScopedToTheAddressedClaim() {
        // Ownership here is claim-scoping (this schema has no tenant column, see
        // repo notes): the reason is loaded and written only through the claim id
        // that was addressed, so it can never be attached to another claim.
        Claim other = new Claim();
        other.setId(4L);
        when(claims.findById(4L)).thenReturn(Optional.of(other));
        ArgumentCaptor<DenialReason> saved = ArgumentCaptor.forClass(DenialReason.class);

        service.recordReason(4L,
                new DenialReasonRequest(DenialGroupCode.PR, "1", null, null, null));

        verify(claims).findById(4L);
        verify(denialReasons).save(saved.capture());
        assertThat(saved.getValue().getClaim().getId()).isEqualTo(4L);
    }

    @Test
    void codesAreNormalizedToUpperCase() {
        when(claims.findById(4L)).thenReturn(Optional.of(claim));
        ArgumentCaptor<DenialReason> captor = ArgumentCaptor.forClass(DenialReason.class);

        service.recordReason(4L,
                new DenialReasonRequest(DenialGroupCode.CO, "a1", "n130", null, null));

        verify(denialReasons).save(captor.capture());
        assertThat(captor.getValue().getCarcCode()).isEqualTo("A1");
        assertThat(captor.getValue().getRarcCode()).isEqualTo("N130");
    }

    @Test
    void listReasonsIsScopedToTheClaim() {
        when(claims.existsById(4L)).thenReturn(true);
        List<DenialReason> rows = new ArrayList<>();
        rows.add(new DenialReason(claim, DenialGroupCode.CO, "197", null, DenialSource.MANUAL, null, "biller"));
        when(denialReasons.findByClaimIdOrderByCreatedAtAsc(4L)).thenReturn(rows);

        List<DenialReasonResponse> res = service.reasonsFor(4L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).claimId()).isEqualTo(4L);
        verify(denialReasons).findByClaimIdOrderByCreatedAtAsc(4L);
    }

    @Test
    void listReasonsForUnknownClaimIsNotFound() {
        when(claims.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.reasonsFor(999L))
                .isInstanceOf(NotFoundException.class);
    }
}
