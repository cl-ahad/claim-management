package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.ConflictException;
import com.claire.claims.domain.Claim;
import com.claire.claims.domain.ClaimStatus;
import com.claire.claims.domain.ClaimStatusHistory;
import com.claire.claims.dto.ClaimDtos.TransitionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The appeal-related transitions in the real state machine, with no mocks: the
 * machine is a pure component. Proves that every appeal status change is legal,
 * mutates the claim, and RETURNS an audit row - the pattern the service then
 * persists. This is where "OVERTURNED => PAID + audit row" and
 * "PARTIAL => PARTIALLY_PAID + audit row" are proven against the machine itself.
 */
class ClaimStatusMachineAppealTest {

    private ClaimStatusMachine machine;
    private Claim claim;

    @BeforeEach
    void setUp() {
        machine = new ClaimStatusMachine();
        claim = new Claim();
        claim.setClaimNumber("CLM-2026-000004");
        claim.setTotalCharge(new BigDecimal("500.00"));
        claim.setAllowedAmount(new BigDecimal("500.00"));
    }

    @Test
    void deniedToAppealedIsLegalAndWritesAnAuditRow() {
        claim.setStatus(ClaimStatus.DENIED);

        ClaimStatusHistory audit = machine.apply(claim,
                new TransitionRequest(ClaimStatus.APPEALED, "appeal filed", null, null), "biller");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPEALED);
        assertThat(audit.getFromStatus()).isEqualTo(ClaimStatus.DENIED);
        assertThat(audit.getToStatus()).isEqualTo(ClaimStatus.APPEALED);
        assertThat(audit.getChangedBy()).isEqualTo("biller");
    }

    @Test
    void overturnedDrivesAppealedToPaidWithAnAuditRow() {
        claim.setStatus(ClaimStatus.APPEALED);

        ClaimStatusHistory audit = machine.apply(claim,
                new TransitionRequest(ClaimStatus.PAID, "overturned",
                        new BigDecimal("500.00"), new BigDecimal("500.00")), "biller");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PAID);
        assertThat(claim.getPaidAmount()).isEqualByComparingTo("500.00");
        assertThat(audit.getFromStatus()).isEqualTo(ClaimStatus.APPEALED);
        assertThat(audit.getToStatus()).isEqualTo(ClaimStatus.PAID);
    }

    @Test
    void partialDrivesAppealedToPartiallyPaidWithAnAuditRow() {
        claim.setStatus(ClaimStatus.APPEALED);

        ClaimStatusHistory audit = machine.apply(claim,
                new TransitionRequest(ClaimStatus.PARTIALLY_PAID, "partial",
                        new BigDecimal("200.00"), new BigDecimal("500.00")), "biller");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PARTIALLY_PAID);
        assertThat(claim.getPaidAmount()).isEqualByComparingTo("200.00");
        assertThat(audit.getFromStatus()).isEqualTo(ClaimStatus.APPEALED);
        assertThat(audit.getToStatus()).isEqualTo(ClaimStatus.PARTIALLY_PAID);
    }

    @Test
    void upheldDrivesAppealedBackToDeniedWithAnAuditRow() {
        claim.setStatus(ClaimStatus.APPEALED);

        ClaimStatusHistory audit = machine.apply(claim,
                new TransitionRequest(ClaimStatus.DENIED, "upheld", null, null), "biller");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.DENIED);
        assertThat(audit.getToStatus()).isEqualTo(ClaimStatus.DENIED);
    }

    @Test
    void appealedToVoidIsLegal() {
        claim.setStatus(ClaimStatus.APPEALED);

        ClaimStatusHistory audit = machine.apply(claim,
                new TransitionRequest(ClaimStatus.VOID, "void", null, null), "admin");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.VOID);
        assertThat(audit.getToStatus()).isEqualTo(ClaimStatus.VOID);
    }

    @Test
    void deniedCanStillBeVoided() {
        claim.setStatus(ClaimStatus.DENIED);

        ClaimStatusHistory audit = machine.apply(claim,
                new TransitionRequest(ClaimStatus.VOID, "void", null, null), "admin");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.VOID);
        assertThat(audit.getToStatus()).isEqualTo(ClaimStatus.VOID);
    }

    @Test
    void deniedCannotJumpStraightToPaid() {
        claim.setStatus(ClaimStatus.DENIED);

        assertThatThrownBy(() -> machine.apply(claim,
                new TransitionRequest(ClaimStatus.PAID, "no", new BigDecimal("500.00"), null), "biller"))
                .isInstanceOf(ConflictException.class);
    }
}
