package com.claire.claims.service;

import com.claire.claims.common.ApiExceptions.BusinessRuleException;
import com.claire.claims.common.ApiExceptions.NotFoundException;
import com.claire.claims.domain.Claim;
import com.claire.claims.domain.DenialReason;
import com.claire.claims.domain.DenialSource;
import com.claire.claims.dto.DenialDtos.DenialReasonRequest;
import com.claire.claims.dto.DenialDtos.DenialReasonResponse;
import com.claire.claims.repository.ClaimRepository;
import com.claire.claims.repository.DenialReasonRepository;
import com.claire.claims.security.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records and reads the structured reasons a claim was denied.
 *
 * A reason is only valid if it carries a group code and a CARC code - free
 * text alone is rejected. A claim may accumulate several reasons. Rows are
 * additive and never mutated: an automated (REMITTANCE) reason takes
 * precedence over a manually keyed one for display, but the manual row is
 * kept for the audit trail.
 */
@Service
public class DenialService {

    private final ClaimRepository claims;
    private final DenialReasonRepository denialReasons;
    private final CurrentUserProvider currentUser;

    public DenialService(ClaimRepository claims,
                         DenialReasonRepository denialReasons,
                         CurrentUserProvider currentUser) {
        this.claims = claims;
        this.denialReasons = denialReasons;
        this.currentUser = currentUser;
    }

    /**
     * Records one structured denial reason against a claim.
     *
     * @throws NotFoundException     if the claim does not exist (query is scoped to the addressed id)
     * @throws BusinessRuleException if the reason carries no structured code
     */
    @Transactional
    public DenialReasonResponse recordReason(Long claimId, DenialReasonRequest request) {
        Claim claim = claims.findById(claimId)
                .orElseThrow(() -> new NotFoundException("Claim", claimId));

        // Defence in depth: Bean Validation already rejects a missing group or
        // CARC code with a 400, but the rule is stated here too so it holds
        // however the service is called.
        if (request.groupCode() == null
                || request.carcCode() == null || request.carcCode().isBlank()) {
            throw new BusinessRuleException(
                    "A structured denial reason requires a group code (CO/PR/OA/PI) and a CARC code; "
                    + "free text alone is not sufficient.");
        }

        // This endpoint keys reasons by hand; automated reasons are posted by
        // the 835 importer. Default to MANUAL when the caller does not say.
        DenialSource source = request.source() == null ? DenialSource.MANUAL : request.source();

        DenialReason reason = new DenialReason(
                claim,
                request.groupCode(),
                request.carcCode().trim().toUpperCase(),
                normalizeRarc(request.rarcCode()),
                source,
                request.note(),
                currentUser.username());

        return toResponse(denialReasons.save(reason));
    }

    /**
     * Every reason recorded against a claim, in the order they were captured,
     * scoped to the addressed claim.
     */
    @Transactional(readOnly = true)
    public List<DenialReasonResponse> reasonsFor(Long claimId) {
        if (!claims.existsById(claimId)) {
            throw new NotFoundException("Claim", claimId);
        }
        return denialReasons.findByClaimIdOrderByCreatedAtAsc(claimId).stream()
                .map(DenialService::toResponse)
                .toList();
    }

    private static String normalizeRarc(String rarc) {
        if (rarc == null || rarc.isBlank()) {
            return null;
        }
        return rarc.trim().toUpperCase();
    }

    private static DenialReasonResponse toResponse(DenialReason r) {
        return new DenialReasonResponse(
                r.getId(),
                r.getClaim().getId(),
                r.getGroupCode(),
                r.getCarcCode(),
                r.getRarcCode(),
                r.getSource(),
                r.getNote(),
                r.getCreatedBy(),
                r.getCreatedAt());
    }
}
