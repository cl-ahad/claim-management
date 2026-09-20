package com.claire.claims.web;

import com.claire.claims.dto.DenialDtos.DenialReasonRequest;
import com.claire.claims.dto.DenialDtos.DenialReasonResponse;
import com.claire.claims.service.DenialService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Structured denial reasons on a claim.
 *
 * Recording a reason is a billing action (ADMIN/BILLER); reading the reasons
 * is open to any authenticated user, including VIEWER, matching the read-only
 * worklist. Every reason is addressed through its claim, so the query is
 * scoped to that claim id.
 */
@RestController
@RequestMapping("/api/claims/{claimId}/denial-reasons")
public class DenialController {

    private final DenialService denials;

    public DenialController(DenialService denials) {
        this.denials = denials;
    }

    @GetMapping
    public List<DenialReasonResponse> list(@PathVariable Long claimId) {
        return denials.reasonsFor(claimId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','BILLER')")
    public ResponseEntity<DenialReasonResponse> record(@PathVariable Long claimId,
                                                       @Valid @RequestBody DenialReasonRequest request) {
        DenialReasonResponse created = denials.recordReason(claimId, request);
        return ResponseEntity
                .created(URI.create("/api/claims/" + claimId + "/denial-reasons/" + created.id()))
                .body(created);
    }
}
